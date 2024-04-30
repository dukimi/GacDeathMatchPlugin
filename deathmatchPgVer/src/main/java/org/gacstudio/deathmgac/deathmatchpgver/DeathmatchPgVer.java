package org.gacstudio.deathmgac.deathmatchpgver;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class DeathmatchPgVer extends JavaPlugin implements Listener {
    private boolean gameStarted = false;
    private int maxDeaths = 2; // 최대 죽은 횟수
    private Map<Player, Integer> playerLives; // 플레이어 목숨 매핑

    private String worldName;
    private Scoreboard scoreboard;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("deathMatch").setTabCompleter(new TabComplete());
        gameStarted = false;
        playerLives = new HashMap<>();

        loadConfig();
        String worldName = getWorldNameFromConfig();
        Bukkit.getWorld(worldName);

        getLogger().info("GacDeathMatch Plugin Version이 활성화되었습니다.");
    }

    @Override
    public void onDisable() {
        stopScheduler(); // 작업 중지
        gameStarted = false; // 게임 상태 변경
        getLogger().info("GacDeathMatch Plugin Version이 비활성화되었습니다.");

        stopScoreboard();
    }

    // 플레이어 죽음 이벤트 핸들러
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if(gameStarted)
        {
            Player player = event.getEntity();
            decreasePlayerLife(player);
            updateScoreboard(player);
            checkPlayersWatching();
        }
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event)
    {
        if(gameStarted)
        {
            Player player = event.getPlayer();
            playerLives.put(player, 0);
            decreasePlayerLife(player);
            updateScoreboard(player);
            checkPlayersWatching();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        if(gameStarted)
        {
            Player player = event.getPlayer();
            playerLives.put(player, 0);
            updateScoreboard(player);
            checkPlayersWatching();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 서버 콘솔 또는 서버 관리자만 해당 명령어를 사용할 수 있도록 설정
        if (!(sender instanceof ConsoleCommandSender) && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "이 명령어를 사용할 권한이 없습니다.");
            return true;
        }
        // 명령어를 사용하는 플레이어의 위치를 확인합니다.
        if (sender instanceof Player) {
            Player player = (Player) sender;
            // 플레이어가 원하는 월드에 있는지 확인합니다.
            if (!player.getWorld().getName().equalsIgnoreCase(worldName)) {
                player.sendMessage(ChatColor.RED + "해당 명령어는 " + worldName + " 세계에서만 사용할 수 있습니다.");
                return true;
            }
        }
        // deathMatch ready
        if (label.equalsIgnoreCase("deathMatch") && args.length > 0 && args[0].equalsIgnoreCase("ready")) {
            if (gameStarted) {
                sender.sendMessage(ChatColor.YELLOW + "진행 중인 데스매치를 중지합니다.");
                stopScheduler(); // 자기장 축소 작업 중지
                gameStarted = false; // 게임 상태 변경
            }

            setupScoreboard();

            // 스코어보드에서 온라인 플레이어를 제외한 모든 플레이어를 제거
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            Objective objective = scoreboard.getObjective("lives");
            if (objective != null) {
                for (String entry : objective.getScoreboard().getEntries()) {
                    // 온라인 플레이어가 아닌 경우에만 제거
                    if (Bukkit.getPlayer(entry) == null) {
                        objective.getScoreboard().resetScores(entry);
                    }
                }
            }

            // 플레이어 목숨 초기화
            playerLives.clear();
            for (Player p : Bukkit.getOnlinePlayers()) {
                playerLives.put(p, maxDeaths);
            }

            setupScoreboard();
            setWorldBorderCenter();
            setWorldSpawnToCenter();
            for (Player p : Bukkit.getOnlinePlayers()) {
                teleportPlayerToSafeLocation(p);
                p.setGameMode(GameMode.CREATIVE);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                p.sendMessage(ChatColor.YELLOW + "준비 단계가 모두 완료되었습니다!");
            }
            return true;
        }

        // deathMatch setMaxDeaths <최대 횟수> 명령어 처리
        if (label.equalsIgnoreCase("deathMatch") && args.length > 0 && args[0].equalsIgnoreCase("setMaxDeaths")) {
            // 게임이 이미 시작되었는지 확인
            if (gameStarted) {
                sender.sendMessage(ChatColor.RED + "게임 시작 이후에는 수정할 수 없습니다!");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /deathMatch setMaxDeaths <최대 횟수>");
                return true;
            }

            try {
                int newMaxDeaths = Integer.parseInt(args[1]);
                if (newMaxDeaths <= 0) {
                    sender.sendMessage(ChatColor.RED + "최대 횟수는 0보다 커야 합니다.");
                    return true;
                }
                maxDeaths = newMaxDeaths;
                sender.sendMessage(ChatColor.GREEN + "최대 횟수가 " + maxDeaths + "로 설정되었습니다.\n/deathmatch ready로 적용하세요!");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "숫자를 입력해주세요.");
            }
            return true;
        }

        // deathMatch start
        if (label.equalsIgnoreCase("deathMatch") && args.length > 0 && args[0].equalsIgnoreCase("start")) {
            // 게임이 이미 시작되었는지 확인
            if (gameStarted) {
                sender.sendMessage(ChatColor.RED + "이미 게임이 시작되었습니다!");
                return true;
            }
            // 게임 시작
            startGame();
            return true;
        }
        return false;
    }

    private void startGame() {
        // 게임 시작
        gameStarted = true;

        // 게임이 곧 시작됩니다 메시지 표시
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.0f, 1.0f);
            sendBigTitle(player, ChatColor.YELLOW + "게임이 곧 시작됩니다!");
        }

        // 5초 뒤에 5, 4, 3, 2, 1, 게임 시작 메시지 표시
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (int i = 5; i > 0; i--) {
                final int count = i;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (count > 3) {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            sendBigTitle(player, ChatColor.YELLOW + String.valueOf(count));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                        }
                    } else {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            sendBigTitle(player, ChatColor.RED + String.valueOf(count));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                        }
                    }
                }, (5 - i) * 20L); // 1초마다 실행
            }

            // 게임 시작 메시지 표시
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    teleportPlayerToSafeLocation(player);
                    player.setHealth(player.getMaxHealth());  // 체력
                    player.setFoodLevel(20);  // 배고픔
                    player.setSaturation(20.0f); // 포화상태
                    player.setGameMode(GameMode.ADVENTURE);
                    player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f);
                    sendBigTitle(player, ChatColor.RED + "게임 시작!");
                }
                Bukkit.getScheduler().runTaskTimer(this, this::decreaseWorldBorderSize, 20 * 60 * 3, 20 * 60 * 3);

                // 월드보더 크기 변화 감지하여 알림 전송
                Bukkit.getScheduler().runTaskTimer(this, () -> {
                    int worldBorderSize = (int) Bukkit.getWorld(worldName).getWorldBorder().getSize();
                    int borderNumber;

                    if (worldBorderSize >= 500) {
                        borderNumber = 1;
                    } else if (worldBorderSize >= 400) {
                        borderNumber = 2;
                    } else if (worldBorderSize >= 300) {
                        borderNumber = 3;
                    } else if (worldBorderSize >= 200) {
                        borderNumber = 4;
                    } else if (worldBorderSize >= 100) {
                        borderNumber = 5;
                    } else {
                        borderNumber = 0;
                    }
                    if (borderNumber > 0) {
                        int[] reminders = {180, 120, 60, 30, 10, 5, 4, 3, 2, 1}; // 각 알림 시간 (초)
                        for (int reminder : reminders) {
                            int delay = (180 - reminder) * 20; // 알림 시간을 틱 단위로 변환
                            Bukkit.getScheduler().runTaskLater(this, () -> {
                                String message = ChatColor.RED + "[ ! ] " + ChatColor.YELLOW + borderNumber + "단계 자기장까지 " + reminder + "초 남았습니다!";
                                for (Player player : Bukkit.getOnlinePlayers()) {
                                    player.sendMessage(message);
                                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                                }
                            }, delay);
                        }
                    }
                }, 0L, 20 * 60 * 3); // 3분마다 실행
            }, 100L); // 5초 후에 실행
        }, 100L); // 5초 후에 실행
    }

    // 목숨 감소 및 관전 모드 변경
    private void decreasePlayerLife(Player player) {
        int lives = playerLives.getOrDefault(player, maxDeaths); // 현재 목숨 가져오기
        lives--; // 목숨 감소
        if (lives < 0) { // 목숨이 음수가 되지 않도록 확인
            lives = 0;
        }
        playerLives.put(player, lives); // 감소된 목숨 설정
        updateScoreboard(player);
        if (lives <= 0) {
            player.setGameMode(GameMode.SPECTATOR); // 목숨이 0이면 관전 모드로 변경
            broadcastPlayerEliminated(player);
        }
    }

    // 한 명을 제외한 모든 플레이어가 목숨이 0 이하이면 승리 처리
    private void checkPlayersWatching() {
        int playersWithLives = 0;
        Player lastPlayer = null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            int lives = playerLives.getOrDefault(player, maxDeaths);
            if (lives > 0) {
                playersWithLives++;
                lastPlayer = player;
            }
        }

        // 한 명을 제외한 모든 플레이어의 목숨이 0 이하일 때
        if (playersWithLives == 1 && lastPlayer != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                sendBigTitle(player, ChatColor.GREEN + "승리: " + lastPlayer.getName());
                player.sendMessage(ChatColor.GREEN + lastPlayer.getName() + "(이)가 최종적으로 승리했습니다!");
            }

            stopScheduler(); // 작업 중지
            gameStarted = false; // 게임 상태 변경
        }
    }

    // 플레이어 탈락 메시지 브로드캐스트
    private void broadcastPlayerEliminated(Player eliminatedPlayer) {
        String message = ChatColor.RED + eliminatedPlayer.getName() + "(이)가 탈락했습니다!";
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(eliminatedPlayer.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 1.0f);
            player.sendMessage(message);
        }
    }
    // 스코어보드 설정
    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard(); // 새로운 스코어보드 생성
        Objective objective = scoreboard.registerNewObjective("lives", "dummy", ChatColor.RED + "목숨");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // 모든 플레이어의 목숨 수를 스코어보드에 표시
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 월드 이름에 플레이어가 있을 때만 스코어보드에 표시
            if (player.getWorld().getName().equals(worldName)) {
                int lives = playerLives.getOrDefault(player, maxDeaths);
                Score score = objective.getScore(player.getName());

                score.setScore(lives);
            }
        }
    }
    // 스코어보드 제거 메서드
    private void stopScoreboard() {
        if (scoreboard != null) {
            scoreboard.getObjective("lives").unregister(); // 목숨 표시 Objective 제거
            scoreboard = null; // 스코어보드 객체 초기화
        }
    }
    // 스코어보드 업데이트
    private void updateScoreboard(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective objective = scoreboard.getObjective("lives");
        if (objective != null) {
            Score score = objective.getScore(player.getName());
            score.setScore(playerLives.getOrDefault(player, maxDeaths));
        }
    }
    // 월드 보더 크기를 줄이는 메서드
    private void decreaseWorldBorderSize() {
        World world = Bukkit.getWorld(worldName);

        // 월드가 존재하는지 확인합니다.
        if (world != null) {
            WorldBorder worldBorder = world.getWorldBorder();
            double newSize = worldBorder.getSize() - 100; // 현재 크기에서 100만큼 감소

            // 보더가 음수 크기가 되지 않도록 확인합니다.
            if (newSize >= 0) {
                worldBorder.setSize(newSize);

                // 자기장 크기가 1 이하인 경우 플레이어들에게 계속 대미지를 입히는 타이머를 시작합니다.
                if (newSize <= 1 && gameStarted) {
                    // 자기장 크기가 1 이하일 때 모든 플레이어들의 목숨을 1로 설정합니다.
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getWorld().getName().equals(worldName)) {
                            playerLives.put(player, 1); // 플레이어의 목숨을 1로 설정
                        }
                    }

                    // 모든 플레이어들에게 지속 대미지 알림을 전송합니다.
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.0f, 1.0f);
                        player.sendMessage(ChatColor.RED + "이제 지속 대미지가 들어오며, 사망 시 다시 부활할 수 없습니다!");
                    }

                    // 플레이어들에게 지속 대미지를 입히는 타이머를 설정합니다.
                    Bukkit.getScheduler().runTaskTimer(this, () -> {
                        // 자기장 크기가 1 이하일 때 플레이어들에게 계속 대미지를 입힙니다.
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.getWorld().getName().equals(worldName)) {
                                player.damage(1); // 대미지 입히기
                            }
                        }
                    }, 10L, 10L);
                }
            } else {
                getLogger().warning("자기장 크기가 음수가 되었습니다.");
            }
        } else {
            getLogger().warning("월드를 찾을 수 없습니다.");
        }
    }
    // 현재 플러그인에 등록된 모든 스케줄러를 취소.
    private void stopScheduler() {
        Bukkit.getScheduler().cancelTasks(this);
    }
    // 세계 스폰 지점을 월드 보더의 중심으로 설정하는 함수
    private void setWorldSpawnToCenter() {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            WorldBorder worldBorder = world.getWorldBorder();
            Location centerLocation = new Location(world, worldBorder.getCenter().getX(), world.getHighestBlockYAt(worldBorder.getCenter()), worldBorder.getCenter().getZ());
            world.setSpawnLocation(centerLocation);
        } else {
            getLogger().warning("월드를 찾을 수 없습니다.");
        }
    }
    // 큰 제목으로 메시지를 보내는 함수
    private void sendBigTitle(Player player, String message) {
        player.sendTitle(message, "", 10, 70, 20);
    }
    // 월드보더 중심 랜덤설정 메인코드
    public void setWorldBorderCenter() {
        World world = Bukkit.getWorld(worldName);

        // 월드가 있는지 확인합니다.
        if (world != null) {
            // 월드의 월드 보더 객체를 가져옵니다.
            WorldBorder worldBorder = world.getWorldBorder();

            // 중심 좌표를 0,0에서 500 블록 사이의 랜덤한 위치로 설정합니다.
            Random random = new Random();
            double centerX = random.nextDouble() * 500 - 250; // -250에서 250 사이의 랜덤한 X 좌표
            double centerZ = random.nextDouble() * 500 - 250; // -250에서 250 사이의 랜덤한 Z 좌표

            // 월드 보더의 크기를 500 블록으로 설정합니다.
            worldBorder.setSize(500);

            // 중심 좌표를 설정합니다.
            worldBorder.setCenter(centerX, centerZ);

            getLogger().info("월드 보더의 중심이 X: " + centerX + ", Z: " + centerZ + "로 설정되었습니다.");
        } else {
            getLogger().warning("월드를 찾을 수 없습니다.");
        }
    }
    // 명령어 사용 제한
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.startsWith("/spawnpoint") || message.startsWith("/setworldspawn")) {
            event.setCancelled(true); // 명령어 취소
            event.getPlayer().sendMessage("해당 명령어는 사용할 수 없습니다.");
        }
    }
    // 안전한 위치 찾는 메인코드
    public void teleportPlayerToSafeLocation(Player player) {
        World world = player.getWorld();
        WorldBorder worldBorder = world.getWorldBorder();
        double borderSize = worldBorder.getSize();

        Location safeLocation = findSafeLocation(world, borderSize);
        if (safeLocation != null) {
            player.teleport(safeLocation);
        } else {
            player.sendMessage(ChatColor.RED + "오류: 랜덤 텔레포트 위치 찾기 작업 실패.\n이 메시지가 보인다면 두킴이에게 보고하세요!");
        }
    }
    // 안전한 위치 찾는 함수
    private Location findSafeLocation(World world, double borderSize) {
        Random random = new Random();
        int maxAttempts = 10;
        int attempt = 0;

        while (attempt < maxAttempts) {
            double x = (random.nextDouble() - 0.5) * borderSize;
            double z = (random.nextDouble() - 0.5) * borderSize;

            // 해당 위치의 블록의 높이를 고려하여 안전한 위치를 설정합니다.
            int y = world.getHighestBlockYAt((int) x, (int) z);
            Location testLocation = new Location(world, x, y + 2, z);

            // 월드 보더의 경계와 충돌하지 않는지 확인합니다.
            if (!worldBorderCollides(world, testLocation)) {
                return testLocation;
            }

            attempt++;
        }
        return null; // 안전한 위치를 찾을 수 없을 때
    }
    // 월드 보더와 충돌하는지 확인하는 함수
    private boolean worldBorderCollides(World world, Location location) {
        WorldBorder worldBorder = world.getWorldBorder();
        double borderSize = worldBorder.getSize();
        Location center = worldBorder.getCenter();

        double distanceX = Math.abs(location.getX() - center.getX());
        double distanceZ = Math.abs(location.getZ() - center.getZ());

        return distanceX >= borderSize / 2 || distanceZ >= borderSize / 2;
    }

    private void loadConfig() {
        // 설정 파일이 존재하지 않으면 기본 설정 파일을 생성합니다.
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }
    }
    private String getWorldNameFromConfig() {
        FileConfiguration config = getConfig();
        worldName = config.getString("worldName", getServer().getWorlds().get(0).getName()); // 기본값으로 "level-name을 설정"
        return worldName;
    }
}

package org.gacstudio.deathmgac.deathmatchpgver;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class TabComplete implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // 만약 args의 길이가 1이라면, 첫 번째 인자를 기준으로 Tab 자동완성을 제안합니다.
        if (args.length == 1) {
            completions.add("start");
            completions.add("ready");
            completions.add("setMaxDeaths");
        }

        // 예외 처리와 기본값 반환
        return completions;
    }
}

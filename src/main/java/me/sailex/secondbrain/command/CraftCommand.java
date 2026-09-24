package me.sailex.secondbrain.command;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

public class CraftCommand implements TabExecutor {
    private final SecondBrainPlugin plugin;
    public CraftCommand(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length < 3) return true;
        NPCData npc = plugin.getNpcManager().findById(args[0]);
        if (npc == null) return true;
        int id; try { id = Integer.parseInt(args[1]); } catch (NumberFormatException e) { return true; }
        if ("approve".equalsIgnoreCase(args[2])) plugin.getNpcCrafting().approve(npc.getId(), id, p);
        else if ("deny".equalsIgnoreCase(args[2])) plugin.getNpcCrafting().deny(npc.getId(), id, p);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { return List.of(); }
}

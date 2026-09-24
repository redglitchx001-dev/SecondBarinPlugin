package me.sailex.secondbrain.command;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses [CMD:/command args] tags out of NPC AI replies and executes them safely.
 *
 * Safety rules (in order):
 *  1. The NPC must have canExecuteCommands=true (set via /sb set <name> commands on).
 *  2. The TALKING player must be an OP (or /sb test console sender → always allowed).
 *     Non-OP players can never trigger command execution, even if the NPC has it on.
 *  3. By default commands dispatch AS THE PLAYER, so land-claim / protection / anti-grief
 *     plugins still block illegal actions the same way they would block the player typing.
 *  4. If the NPC is also flagged "op" (trusted operator NPC — set via /sb op <name>),
 *     commands dispatch as console. Only OPs can grant this flag.
 *  5. A hardcoded blocklist prevents /op /deop /stop /reload /save-off /paper /plugins etc.
 *     even for console NPCs (defense in depth).
 */
public class CommandExecutor {

    private static final Pattern CMD_PATTERN = Pattern.compile("\\[CMD:(/[^\\]]+)\\]");

    private static final List<String> BLOCKED_ROOTS = List.of(
            "op", "deop", "stop", "restart", "reload", "rl",
            "save-all", "save-off", "save-on",
            "paper", "plugins", "pl", "version", "ver", "?", "help", "about", "bukkit",
            "whitelist", "ban-ip", "pardon-ip", "setworldspawn",
            "grant", "luckperms", "lp", "perms", "perm"
    );

    private final SecondBrainPlugin plugin;

    public CommandExecutor(SecondBrainPlugin plugin) { this.plugin = plugin; }

    /**
     * Extract commands out of a reply, execute them, then return the reply with
     * all [CMD:...] tags stripped. playerName may be null (console / /sb test).
     */
    public String executeAndStrip(NPCData npc, String playerName, String reply) {
        if (reply == null) return null;

        Matcher m = CMD_PATTERN.matcher(reply);
        List<String> cmds = new java.util.ArrayList<>();
        while (m.find()) cmds.add(m.group(1));

        if (cmds.isEmpty()) return reply;

        if (npc == null || !npc.canExecuteCommands()) return strip(reply);

        // Speaker must be OP (or be the console via /sb test).
        Player talker = playerName == null ? null : Bukkit.getPlayerExact(playerName);
        boolean talkerIsOp = talker == null || talker.isOp();
        if (!talkerIsOp) return strip(reply);

        boolean asConsole = npc.isConsoleExecutor();
        CommandSender sender = asConsole ? Bukkit.getConsoleSender() : talker;
        if (sender == null) return strip(reply);

        for (String raw : cmds) {
            String expanded = raw
                    .replace("{player}", playerName == null ? "" : playerName)
                    .replace("{name}", npc.getName())
                    .trim();
            String root = rootOf(expanded);
            if (isBlocked(root)) {
                plugin.getLogger().warning("Blocked NPC command from " + npc.getName()
                        + " (talker=" + playerName + "): " + expanded);
                continue;
            }
            final String toRun = expanded.startsWith("/") ? expanded.substring(1) : expanded;
            final CommandSender s = sender;
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(s, toRun));
        }

        return strip(reply);
    }

    private String strip(String reply) {
        return CMD_PATTERN.matcher(reply).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    }

    private String rootOf(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        int sp = c.indexOf(' ');
        return (sp < 0 ? c : c.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isBlocked(String root) {
        for (String b : BLOCKED_ROOTS) if (b.equals(root)) return true;
        return false;
    }
}

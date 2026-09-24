package me.sailex.secondbrain.command;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.config.ConfigManager;
import me.sailex.secondbrain.npc.NPCData;
import me.sailex.secondbrain.npc.NPCManager;
import me.sailex.secondbrain.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every /sb subcommand. */
public class SecondBrainCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "gui", "create", "clone", "remove", "removeall", "rename", "list", "info", "near",
            "tp", "move", "tphere", "setprompt", "prompt", "setradius", "settype", "setprofession",
            "setskin", "clearskin", "hold", "inv", "inventory", "op", "deop",
            "set", "toggle", "clearmemory", "focus", "unfocus", "test",
            "setkey", "seturl", "setmodel", "status", "stats", "save", "reload", "version", "more"
    );

    private static final List<String> GLOBAL_TOGGLES = List.of(
            "chat", "nameonly", "look", "nametags", "glow", "typing", "debug");
    private static final List<String> NPC_TOGGLES = List.of(
            "chat", "nameonly", "look", "nametag", "glow", "baby", "commands", "hostile", "pickup");
    private static final List<String> ON_OFF = List.of("on", "off");
    private static final List<String> ON_OFF_DEFAULT = List.of("on", "off", "default");

    private final SecondBrainPlugin plugin;

    public SecondBrainCommand(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (args.length == 0) {
            if (s instanceof Player p) plugin.getGuiManager().openMainMenu(p);
            else help(s, 1);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        var cm = plugin.getConfigManager();
        var nm = plugin.getNpcManager();

        switch (sub) {
            case "help" -> {
                int page = 1;
                if (args.length > 1) page = Math.max(1, parseInt(args[1], 1));
                help(s, page);
            }
            case "gui", "menu" -> {
                if (needPlayer(s)) {
                    if (perm(s, "secondbrain.use")) plugin.getGuiManager().openMainMenu((Player) s);
                }
            }
            case "about", "version" -> s.sendMessage(cm.msgRaw("prefix")
                    + "\u00a7bSecondBrain \u00a7fv" + plugin.getPluginMeta().getVersion()
                    + " \u00a78- \u00a77AI NPCs, zero dependencies. Original by sailex428.");
            case "create" -> {
                if (!perm(s, "secondbrain.admin") || !needPlayer(s)) return true;
                if (args.length < 2) { usage(s, "/sb create <name>"); return true; }
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                if (!Text.validNpcName(name)) {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a7cNames: 1-16 plain letters/numbers/underscores/spaces. &-color codes allowed, e.g. &cBob.");
                    return true;
                }
                String err = nm.createNPC(name, ((Player) s).getLocation());
                if (err != null) {
                    if ("invalid-name".equals(err)) s.sendMessage(cm.msgRaw("prefix") + "\u00a7cInvalid name.");
                    else s.sendMessage(cm.msg(err, "name", name));
                } else {
                    s.sendMessage(cm.msg("created", "name", Text.color(name)));
                }
            }
            case "clone" -> {
                if (!perm(s, "secondbrain.admin") || !needPlayer(s)) return true;
                if (args.length < 3) { usage(s, "/sb clone <sourceName> <newName>"); return true; }
                NPCData src = npcArg(s, args[1]);
                if (src == null) return true;
                String newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                String err = nm.cloneNPC(src, newName, ((Player) s).getLocation());
                if (err != null) {
                    if ("invalid-name".equals(err)) s.sendMessage(cm.msgRaw("prefix") + "\u00a7cInvalid name.");
                    else s.sendMessage(cm.msg(err, "name", newName));
                } else {
                    s.sendMessage(cm.msg("created", "name", Text.color(newName)) + " \u00a77(cloned from \u00a7f" + src.getName() + "\u00a77)");
                }
            }
            case "tphere" -> {
                if (!perm(s, "secondbrain.admin") || !needPlayer(s)) return true;
                if (args.length < 2) { usage(s, "/sb tphere <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                nm.move(npc.getName(), ((Player) s).getLocation());
                s.sendMessage(cm.msg("moved", "name", npc.getName()));
            }
            case "remove" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb remove <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                if (nm.removeByName(npc.getName())) {
                    plugin.getChatService().clearMemory(npc.getId());
                    s.sendMessage(cm.msg("removed", "name", npc.getName()));
                }
            }
            case "removeall" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (s instanceof Player p) {
                    plugin.getGuiManager().confirm(p, "Delete ALL " + nm.getAllNPCs().size() + " NPCs?",
                            () -> {
                                int n = nm.removeAll();
                                plugin.getChatService().clearAllMemories();
                                p.sendMessage(cm.msgRaw("prefix") + "\u00a7cRemoved \u00a7e" + n + "\u00a7c NPC(s).");
                            }, null);
                } else {
                    if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                        s.sendMessage(cm.msgRaw("prefix") + "\u00a7cConsole: run '/sb removeall confirm' to delete ALL NPCs.");
                        return true;
                    }
                    int n = nm.removeAll();
                    plugin.getChatService().clearAllMemories();
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a7cRemoved \u00a7e" + n + "\u00a7c NPC(s).");
                }
            }
            case "rename" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb rename <old> <new>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String newName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                if (!Text.validNpcName(newName)) {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a7cNames: 1-16 plain letters/numbers/underscores/spaces. &-color codes allowed, e.g. &cBob.");
                    return true;
                }
                String oldName = npc.getName();
                if (nm.rename(oldName, newName)) {
                    plugin.getChatService().clearMemory(npc.getId());
                    s.sendMessage(cm.msg("renamed", "old", oldName, "new", Text.color(newName)));
                } else {
                    s.sendMessage(cm.msg("already-exists", "name", newName));
                }
            }
            case "list" -> {
                if (!perm(s, "secondbrain.use")) return true;
                var all = nm.getAllNPCs().values().stream()
                        .sorted(Comparator.comparing(n -> n.getName().toLowerCase(Locale.ROOT)))
                        .toList();
                if (all.isEmpty()) { s.sendMessage(cm.msg("list-empty")); return true; }

                int perPage = 8;
                int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
                int page = args.length > 1 ? Math.max(1, Math.min(parseInt(args[1], 1), pages)) : 1;

                s.sendMessage(cm.msgRaw("list-header", "page", String.valueOf(page), "pages", String.valueOf(pages)));
                int start = (page - 1) * perPage;
                for (int i = start; i < Math.min(start + perPage, all.size()); i++) {
                    NPCData n = all.get(i);
                    s.sendMessage(cm.msgRaw("list-entry",
                            "name", n.getName(),
                            "world", worldName(n),
                            "status", n.statusString(nm.isChatEnabled(n))));
                }
            }
            case "info" -> {
                if (!perm(s, "secondbrain.use")) return true;
                if (args.length < 2) { usage(s, "/sb info <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;

                s.sendMessage("\u00a78======== \u00a7b" + npc.getName() + " \u00a78========");
                s.sendMessage("\u00a77ID: \u00a7f" + npc.getId());
                s.sendMessage("\u00a77Type: \u00a7f" + npc.getEntityType()
                        + (npc.getProfession() != null ? " (" + npc.getProfession() + ")" : "")
                        + (Boolean.TRUE.equals(npc.getBabyRaw()) ? " \u00a7f(baby)" : ""));
                s.sendMessage("\u00a77Location: \u00a7f" + locString(npc));
                s.sendMessage("\u00a77AI chat: " + onOff(nm.isChatEnabled(npc)) + inherit(npc.getChatEnabledRaw()));
                s.sendMessage("\u00a77Name-only: " + onOff(nm.isNameOnly(npc)) + inherit(npc.getNameOnlyRaw()));
                s.sendMessage("\u00a77Look at players: " + onOff(nm.isLookAtPlayers(npc)) + inherit(npc.getLookAtPlayersRaw()));
                s.sendMessage("\u00a77Name tag: " + onOff(nm.isShowName(npc)) + inherit(npc.getShowNameRaw()));
                s.sendMessage("\u00a77Glowing: " + onOff(nm.isGlow(npc)) + inherit(npc.getGlowRaw()));
                s.sendMessage("\u00a77Can run commands: " + onOff(npc.canExecuteCommands()) + inherit(npc.getCanExecuteCommandsRaw())
                        + (npc.isConsoleExecutor() ? " \u00a7c\u00a7l[CONSOLE/OP]\u00a77" : ""));
                s.sendMessage("\u00a77Hostile (PvP): " + onOff(npc.isHostile()) + inherit(npc.getHostileRaw()));
                s.sendMessage("\u00a77Picks up items: " + onOff(npc.canPickupItems()) + inherit(npc.getCanPickupItemsRaw()));
                s.sendMessage("\u00a77XP levels: \u00a7f" + (npc.hasInventory() ? npc.getInventory().getXpLevels() : 0));
                s.sendMessage("\u00a77Holds: \u00a7f" + (npc.getMainHand() == null ? "(nothing)" : npc.getMainHand()));
                s.sendMessage("\u00a77Radius: \u00a7f" + Text.num(nm.getChatRadius(npc)) + inherit(npc.getChatRadiusRaw()));
                s.sendMessage("\u00a77Replies served: \u00a7f" + npc.getRepliesServed());
                s.sendMessage("\u00a77Memory: \u00a7f" + plugin.getChatService().countMessages(npc.getId()) + " messages");
                s.sendMessage("\u00a77Skin: \u00a7f" + (npc.hasSkin() ? npc.getSkinName() : "\u00a77(none)"));
                s.sendMessage("\u00a77Prompt: \u00a7f" + npc.getSystemPrompt());
            }
            case "near" -> {
                if (!perm(s, "secondbrain.use") || !needPlayer(s)) return true;
                Player p = (Player) s;
                double radius = args.length > 1 ? parseDouble(args[1], 30) : 30;
                var nearby = nm.getNearby(p.getLocation(), radius);
                if (nearby.isEmpty()) {
                    s.sendMessage(cm.msg("near-none", "radius", Text.num(radius)));
                    return true;
                }
                p.sendMessage(cm.msgRaw("near-header", "radius", Text.num(radius), "count", String.valueOf(nearby.size())));
                LegacyComponentSerializer ser = LegacyComponentSerializer.legacySection();
                for (NPCData n : nearby) {
                    Location nl = n.getLocation();
                    double dist = nl.distance(p.getLocation());
                    Location eye = p.getEyeLocation();
                    double dx = nl.getX() - eye.getX();
                    double dz = nl.getZ() - eye.getZ();
                    String arrow = Text.direction(p.getLocation().getYaw(), dx, dz);

                    String displayName = n.getName().contains("&") || n.getName().contains("\u00a7")
                            ? Text.color(n.getName()) : "\u00a7e" + n.getName();
                    Component line = ser.deserialize(
                            cm.msgRaw("near-entry",
                                    "name", displayName,
                                    "dist", String.valueOf(Math.round(dist)),
                                    "direction", arrow)
                    );
                    Component tpBtn = Component.text(" [T]", NamedTextColor.AQUA)
                            .decorate(TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + Text.stripColors(n.getName()), NamedTextColor.YELLOW)))
                            .clickEvent(ClickEvent.runCommand("/sb tp " + Text.stripColors(n.getName())));
                    Component focusBtn = Component.text(" [F]", NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text("Focus " + Text.stripColors(n.getName()) + " (chat goes straight to them)", NamedTextColor.YELLOW)))
                            .clickEvent(ClickEvent.runCommand("/sb focus " + Text.stripColors(n.getName())));
                    p.sendMessage(line.append(tpBtn).append(focusBtn));
                }
            }
            case "tp" -> {
                if (!perm(s, "secondbrain.admin") || !needPlayer(s)) return true;
                if (args.length < 2) { usage(s, "/sb tp <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                Location loc = npc.getLocation();
                if (loc == null || loc.getWorld() == null) { s.sendMessage(cm.msg("world-missing")); return true; }
                ((Player) s).teleport(loc);
                s.sendMessage(cm.msg("teleported", "name", npc.getName()));
            }
            case "move" -> {
                if (!perm(s, "secondbrain.admin") || !needPlayer(s)) return true;
                if (args.length < 2) { usage(s, "/sb move <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                nm.move(npc.getName(), ((Player) s).getLocation());
                s.sendMessage(cm.msg("moved", "name", npc.getName()));
            }
            case "setprompt" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb setprompt <name> <text>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String prompt = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                npc.setSystemPrompt(prompt);
                nm.saveAll();
                s.sendMessage(cm.msg("prompt-set", "name", npc.getName()));
            }
            case "prompt" -> {
                if (!perm(s, "secondbrain.use")) return true;
                if (args.length < 2) { usage(s, "/sb prompt <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                s.sendMessage(cm.msgRaw("prompt-header", "name", npc.getName()));
                s.sendMessage("\u00a7f" + npc.getSystemPrompt());
            }
            case "setradius" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb setradius <name> <blocks>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                double r = parseDouble(args[2], -1);
                if (r < 2 || r > 200) { s.sendMessage(cm.msg("invalid-number", "value", args[2])); return true; }
                npc.setChatRadius(r);
                nm.saveAll();
                s.sendMessage(cm.msg("radius-set", "name", npc.getName(), "radius", Text.num(r)));
            }
            case "settype" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb settype <name> <" + typeNames(" | ") + ">"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                EntityType type = matchAllowedType(args[2]);
                if (type == null) {
                    s.sendMessage(cm.msg("type-invalid", "type", args[2], "types", typeNames(", ")));
                    return true;
                }
                npc.setEntityType(type);
                if (type != EntityType.VILLAGER) npc.setProfession(null);
                nm.spawnEntity(npc);
                nm.saveAll();
                s.sendMessage(cm.msg("type-set", "name", npc.getName(), "type", type.name()));
            }
            case "setprofession" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb setprofession <name> <profession>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                if (npc.getEntityType() != EntityType.VILLAGER) {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a77Only villagers have professions.");
                    return true;
                }
                Villager.Profession prof = ConfigManager.parseProfession(args[2]);
                if (prof == null) { s.sendMessage(cm.msg("profession-invalid", "profession", args[2])); return true; }
                npc.setProfession(prof == Villager.Profession.NONE ? null : prof);
                nm.spawnEntity(npc);
                nm.saveAll();
                s.sendMessage(cm.msg("profession-set", "name", npc.getName(), "profession", prof.name()));
            }
            case "setskin" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb setskin <name> <playerName>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String skin = args[2];
                if (!me.sailex.secondbrain.skin.SkinManager.validName(skin)) {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a7cInvalid player name: \u00a7e" + skin);
                    return true;
                }
                npc.setSkinName(skin);
                nm.applySkin(npc);
                nm.saveAll();
                s.sendMessage(cm.msgRaw("prefix") + "\u00a7aFetching skin \u00a7e" + skin + "\u00a7a for \u00a7e" + npc.getName() + "\u00a7a...");
                // Trigger a fetch so the helmet updates when complete.
                plugin.getSkinManager().getSkull(skin, () -> {
                    if (s instanceof Player p && !p.isOnline()) return;
                    nm.applySkin(npc);
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a7aSkin applied to \u00a7e" + npc.getName() + "\u00a7a.");
                });
            }
            case "clearskin" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb clearskin <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                npc.setSkinName(null);
                nm.applySkin(npc);
                nm.saveAll();
                s.sendMessage(cm.msgRaw("prefix") + "\u00a7aSkin removed from \u00a7e" + npc.getName() + "\u00a7a.");
            }
            case "hold" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb hold <name> <material|none>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String mat = args[2];
                if (mat.equalsIgnoreCase("none") || mat.equalsIgnoreCase("clear")) {
                    npc.setMainHand(null);
                } else {
                    Material m = Material.matchMaterial(mat);
                    if (m == null) {
                        s.sendMessage(cm.msgRaw("prefix") + "\u00a7cUnknown material: \u00a7e" + mat);
                        return true;
                    }
                    npc.setMainHand(m.name());
                }
                nm.applyEquipment(npc);
                if (npc.isHostile()) nm.spawnEntity(npc);
                nm.saveAll();
                s.sendMessage(cm.msgRaw("prefix") + "\u00a7e" + npc.getName() + " \u00a7anow holds: \u00a7f" + (npc.getMainHand() == null ? "(nothing)" : npc.getMainHand()));
            }
            case "inv", "inventory" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (!(s instanceof Player p)) { s.sendMessage("\u00a7cOnly players can open inventories."); return true; }
                if (args.length < 2) { usage(s, "/sb inv <name>"); return true; }
                NPCData npc = npcArg(s, args[1]); if (npc == null) return true;
                npc.getInventory().openTo(p);
            }
            case "op" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb op <name>"); return true; }
                NPCData npc = npcArg(s, args[1]); if (npc == null) return true;
                npc.setCanExecuteCommands(true);
                npc.setConsoleExecutor(true);
                nm.saveAll();
                s.sendMessage(cm.msgRaw("prefix") + "\u00a76\u00a7l" + npc.getName()
                        + " \u00a76is now a trusted \u00a7cOP NPC\u00a76. Commands will run as console when an OP talks to them. \u00a7cOnly do this for NPCs you trust!");
            }
            case "deop" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb deop <name>"); return true; }
                NPCData npc = npcArg(s, args[1]); if (npc == null) return true;
                npc.setConsoleExecutor(false);
                nm.saveAll();
                s.sendMessage(cm.msgRaw("prefix") + "\u00a7a" + npc.getName() + " \u00a7ais no longer an OP NPC.");
            }
            case "more" -> {
                s.sendMessage("\u00a78\u00a7m--------------------------------------------");
                s.sendMessage("  \u00a7d\u00a7lWhat more can we add to SecondBrain?");
                s.sendMessage("\u00a78\u00a7m--------------------------------------------");
                String[][] lines = {
                        {"\u00a7b\u2708", "Ranged combat", "bows, crossbows, tridents (raycast)"},
                        {"\u00a7c\u2694", "Shield blocking", "parry + sweep attacks"},
                        {"\u00a7e\ud83d\udee1", "Armor + offhand", "full armor sets & shields"},
                        {"\u00a75\u2708", "Elytra / crystal PvP", "flight & end-crystal pathing"},
                        {"\u00a76\ud83d\udc65", "Follow / guard", "/sb follow & patrol waypoints"},
                        {"\u00a7a\ud83c\udfe0", "Factions / guards", "claim guards, attack intruders"},
                        {"\u00a72\ud83d\udcb0", "Trading", "villager-style GUIs with custom trades"},
                        {"\u00a7c\ud83d\udde1", "Quests", "dialog trees & objectives"},
                        {"\u00a7d\ud83c\udfad", "Emotes / animations", "sit, wave, dance via packets"},
                        {"\u00a7e\ud83c\udfb5", "Voice", "TTS voice lines (requires ElevenLabs/OpenAI)"},
                        {"\u00a79\ud83d\udcc5", "Schedules", "wake/sleep/work routines"},
                        {"\u00a73\ud83e\udde0", "Long-term memory", "vector/embedding recall"},
                        {"\u00a7c\u2b50", "Boss bars", "health bar + fight music cues"},
                        {"\u00a7e\ud83c\udfaf", "Skill trees", "leveling / unlocks per NPC"},
                        {"\u00a7b\ud83d\udcac", "Parties", "group chat across NPCs"},
                };
                for (String[] l : lines)
                    s.sendMessage(" " + l[0] + " \u00a7f" + l[1] + " \u00a78- " + l[2]);
                s.sendMessage("\u00a77Just tell me which to build next!");
                return true;
            }
            case "set" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 4) { usage(s, "/sb set <name> <" + String.join("|", NPC_TOGGLES) + "> <on|off|default>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String setting = args[2].toLowerCase(Locale.ROOT);
                if (!NPC_TOGGLES.contains(setting)) {
                    s.sendMessage(cm.msg("toggle-invalid", "setting", setting, "options", String.join(", ", NPC_TOGGLES)));
                    return true;
                }
                Boolean val;
                if (args[3].equalsIgnoreCase("default")) val = null;
                else {
                    val = Text.parseBool(args[3]);
                    if (val == null) { s.sendMessage(cm.msg("invalid-bool")); return true; }
                }
                boolean needsRespawn = setting.equals("baby");
                switch (setting) {
                    case "chat" -> npc.setChatEnabled(val);
                    case "nameonly" -> npc.setNameOnly(val);
                    case "look" -> npc.setLookAtPlayers(val);
                    case "nametag" -> npc.setShowName(val);
                    case "glow" -> npc.setGlow(val);
                    case "baby" -> npc.setBaby(val);
                    case "commands" -> npc.setCanExecuteCommands(val);
                    case "hostile" -> { npc.setHostile(val); needsRespawn = true; }
                    case "pickup" -> npc.setCanPickupItems(val);
                    default -> {}
                }
                nm.saveAll();
                nm.applyVisuals(npc);
                nm.applyEquipment(npc);
                if (needsRespawn) nm.spawnEntity(npc);
                s.sendMessage(cm.msg("npc-set",
                        "name", npc.getName(),
                        "setting", setting,
                        "value", val == null ? "\u00a77default" : (val ? "\u00a7aon" : "\u00a7coff")));
            }
            case "toggle" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb toggle <" + String.join("|", GLOBAL_TOGGLES) + "> [on|off]"); return true; }
                String setting = args[1].toLowerCase(Locale.ROOT);
                if (!GLOBAL_TOGGLES.contains(setting)) {
                    s.sendMessage(cm.msg("toggle-invalid", "setting", setting, "options", String.join(", ", GLOBAL_TOGGLES)));
                    return true;
                }
                Boolean val = args.length > 2 ? Text.parseBool(args[2]) : null;
                if (args.length > 2 && val == null) { s.sendMessage(cm.msg("invalid-bool")); return true; }

                boolean result = switch (setting) {
                    case "chat" -> { boolean v = val != null ? val : !cm.isChatEnabled(); cm.setChatEnabled(v); yield v; }
                    case "nameonly" -> { boolean v = val != null ? val : !cm.isRespondOnlyToName(); cm.setRespondOnlyToName(v); yield v; }
                    case "look" -> { boolean v = val != null ? val : !cm.isLookAtPlayers(); cm.setLookAtPlayers(v); yield v; }
                    case "nametags" -> { boolean v = val != null ? val : !cm.isShowName(); cm.setShowName(v); nm.refreshAllVisuals(); yield v; }
                    case "glow" -> { boolean v = val != null ? val : !cm.isGlow(); cm.setGlow(v); nm.refreshAllVisuals(); yield v; }
                    case "typing" -> { boolean v = val != null ? val : !cm.isTypingIndicator(); cm.setTypingIndicator(v); yield v; }
                    case "debug" -> { boolean v = val != null ? val : !cm.isDebug(); cm.setDebug(v); yield v; }
                    default -> false;
                };
                s.sendMessage(cm.msg("toggle-set", "setting", setting, "value", result ? "\u00a7aon" : "\u00a7coff"));
            }
            case "clearmemory" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb clearmemory <name|all>"); return true; }
                if (args[1].equalsIgnoreCase("all")) {
                    plugin.getChatService().clearAllMemories();
                    s.sendMessage(cm.msg("memory-cleared-all"));
                } else {
                    NPCData npc = npcArg(s, args[1]);
                    if (npc == null) return true;
                    plugin.getChatService().clearMemory(npc.getId());
                    s.sendMessage(cm.msg("memory-cleared", "name", npc.getName()));
                }
            }
            case "focus" -> {
                if (!perm(s, "secondbrain.use") || !needPlayer(s)) return true;
                if (args.length < 2) { usage(s, "/sb focus <name>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                plugin.getChatService().setFocus((Player) s, npc);
                s.sendMessage(cm.msg("focus-set", "name", npc.getName(),
                        "seconds", String.valueOf(cm.getFocusDuration())));
            }
            case "unfocus" -> {
                if (!perm(s, "secondbrain.use") || !needPlayer(s)) return true;
                plugin.getChatService().clearFocus((Player) s);
                s.sendMessage(cm.msg("focus-cleared"));
            }
            case "test" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 3) { usage(s, "/sb test <name> <message>"); return true; }
                NPCData npc = npcArg(s, args[1]);
                if (npc == null) return true;
                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

                if (s instanceof Player p) {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a77Testing \u00a7e" + npc.getName() + "\u00a77 (reply also broadcasts in-world)...");
                    plugin.getChatService().sendDirect(npc, p, message);
                } else {
                    s.sendMessage(cm.msgRaw("prefix") + "\u00a77Asking \u00a7e" + npc.getName() + "\u00a77...");
                    plugin.getLlmClient().chat(npc.getSystemPrompt(), "Console", message, new ArrayList<>())
                            .thenAccept(result -> Bukkit.getScheduler().runTask(plugin,
                                    () -> {
                                        String clean = plugin.getCommandExecutor().executeAndStrip(npc, null, result.reply());
                                        s.sendMessage(cm.msgRaw("test-header", "npc", npc.getName()) + " " + clean);
                                    }));
                }
            }
            case "setkey" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb setkey <key>"); return true; }
                cm.setApiKey(args[1]);
                plugin.getLlmClient().reload();
                s.sendMessage(cm.msg("key-saved"));
            }
            case "seturl" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb seturl <url>"); return true; }
                if (!args[1].startsWith("http")) { s.sendMessage(cm.msgRaw("prefix") + "\u00a7cURL must start with http(s)."); return true; }
                cm.setApiUrl(args[1]);
                plugin.getLlmClient().reload();
                s.sendMessage(cm.msg("url-saved", "url", args[1]));
            }
            case "setmodel" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                if (args.length < 2) { usage(s, "/sb setmodel <model>"); return true; }
                cm.setApiModel(args[1]);
                s.sendMessage(cm.msg("model-saved", "model", args[1]));
            }
            case "status" -> {
                if (!perm(s, "secondbrain.use")) return true;
                s.sendMessage(cm.msgRaw("status-header"));
                s.sendMessage(cm.msgRaw("status-url", "value", cm.getApiUrl()));
                s.sendMessage(cm.msgRaw("status-model", "value", cm.getApiModel()));
                s.sendMessage(cm.msgRaw("status-key", "value", Text.maskKey(cm.getApiKey())));
                s.sendMessage(cm.msgRaw("status-timeout", "value", String.valueOf(cm.getTimeout())));
                s.sendMessage(cm.msgRaw("status-limits",
                        "history", String.valueOf(cm.getMaxHistory()),
                        "tokens", String.valueOf(cm.getMaxTokens()),
                        "temp", Text.num(cm.getTemperature())));
                s.sendMessage(cm.msgRaw("status-npcs", "value", String.valueOf(nm.getAllNPCs().size())));
            }
            case "stats" -> {
                if (!perm(s, "secondbrain.use")) return true;
                var stats = plugin.getStats();
                s.sendMessage(cm.msgRaw("stats-header"));
                s.sendMessage(cm.msgRaw("stats-npcs", "value", String.valueOf(nm.getAllNPCs().size())));
                s.sendMessage(cm.msgRaw("stats-memories", "value", String.valueOf(plugin.getChatService().countConversations())));
                s.sendMessage(cm.msgRaw("stats-messages", "value", String.valueOf(stats.getReplies())));
                s.sendMessage(cm.msgRaw("stats-errors", "value", String.valueOf(stats.getErrors())));
                s.sendMessage(cm.msgRaw("stats-latency", "value", stats.avgLatency()));
                s.sendMessage(cm.msgRaw("stats-uptime", "value", Text.uptime(stats.getUptimeMillis())));
            }
            case "save" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                nm.saveAll();
                plugin.getChatService().saveMemoriesNow();
                s.sendMessage(cm.msg("saved"));
            }
            case "reload" -> {
                if (!perm(s, "secondbrain.admin")) return true;
                plugin.reload();
                s.sendMessage(cm.msg("reloaded"));
            }
            default -> help(s, 1);
        }
        return true;
    }

    // ============================================================
    //  Help
    // ============================================================

    private void help(CommandSender s, int page) {
        boolean admin = s.hasPermission("secondbrain.admin");
        List<String> lines = new ArrayList<>();
        lines.add("\u00a7e/sb \u00a77- open the GUI");
        lines.add("\u00a7e/sb list \u00a78[page]\u00a77 - list NPCs");
        lines.add("\u00a7e/sb info <name> \u00a77- NPC details");
        lines.add("\u00a7e/sb near \u00a78[radius]\u00a77 - NPCs around you");
        lines.add("\u00a7e/sb focus <name> \u00a78| /sb unfocus \u00a77- talk without saying the name");
        lines.add("\u00a7e/sb prompt <name> \u00a77- show an NPC's prompt");
        lines.add("\u00a7e/sb status \u00a78| /sb stats \u00a77- connection / statistics");
        if (admin) {
            lines.add("\u00a7e/sb create <name> \u00a78| /sb clone <src> <name> \u00a78| /sb remove <name> \u00a78| /sb removeall");
            lines.add("\u00a7e/sb rename <old> <new> \u00a78| /sb move|tphere|tp <name>");
            lines.add("\u00a7e/sb setprompt <name> <text>");
            lines.add("\u00a7e/sb set <name> <setting> <on|off|default>");
            lines.add("\u00a7e/sb setradius <name> <blocks> \u00a78| /sb settype \u00a78| /sb setprofession");
            lines.add("\u00a7e/sb setskin <name> <player> \u00a78| /sb clearskin <name> \u00a78| /sb hold <name> <mat|none>");
            lines.add("\u00a7e/sb inv <name> \u00a78- open NPC's full inventory (armor, 2x2 craft, furnace)");
            lines.add("\u00a7e/sb toggle <" + String.join("|", GLOBAL_TOGGLES) + "> \u00a78[on|off]");
            lines.add("\u00a7e/sb clearmemory <name|all> \u00a78| /sb test <name> <msg>");
            lines.add("\u00a7e/sb setkey <key> \u00a78| /sb seturl <url> \u00a78| /sb setmodel <model>");
            lines.add("\u00a7c/sb op <name> \u00a78(trusted console NPC) \u00a77| /sb deop <name>");
            lines.add("\u00a7d/sb more \u00a78- ideas for what to add next");
            lines.add("\u00a7e/sb save \u00a78| /sb reload");
        }

        int perPage = 8;
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, pages));

        var cm = plugin.getConfigManager();
        s.sendMessage(cm.msgRaw("help-header", "page", String.valueOf(page), "pages", String.valueOf(pages)));
        int start = (page - 1) * perPage;
        for (int i = start; i < Math.min(start + perPage, lines.size()); i++) {
            s.sendMessage(lines.get(i));
        }
    }

    // ============================================================
    //  Tab completion
    // ============================================================

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        boolean admin = s.hasPermission("secondbrain.admin");
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String cmd : SUBCOMMANDS) {
                boolean adminOnly = !(List.of("help", "gui", "list", "info", "near", "focus", "unfocus",
                        "prompt", "status", "stats", "version").contains(cmd));
                if (adminOnly && !admin) continue;
                if (cmd.startsWith(sub)) out.add(cmd);
            }
            return out;
        }

        String arg1 = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (arg1) {
                case "toggle" -> filter(GLOBAL_TOGGLES, args[1]);
                case "near" -> List.of("10", "20", "30", "50");
                case "help", "list" -> List.of("1", "2");
                case "clearmemory" -> filter(npcNamesPlus("all"), args[1]);
                case "remove", "rename", "info", "tp", "move", "tphere", "clone",
                     "setprompt", "prompt", "setradius", "settype", "setprofession",
                     "setskin", "clearskin", "hold", "inv", "inventory", "op", "deop", "set", "test", "focus" -> filter(npcNames(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (arg1) {
                case "toggle" -> filter(ON_OFF, args[2]);
                case "set" -> filter(NPC_TOGGLES, args[2]);
                case "settype" -> filter(typeList(), args[2]);
                case "setprofession" -> filter(professionNames(), args[2]);
                case "rename" -> List.of("<newName>");
                default -> List.of();
            };
        }
        if (args.length == 4 && arg1.equals("set")) {
            return filter(ON_OFF_DEFAULT, args[3]);
        }
        return List.of();
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private List<String> npcNames() {
        List<String> out = new ArrayList<>();
        for (NPCData d : plugin.getNpcManager().getAllNPCs().values()) out.add(d.getName());
        return out;
    }

    private List<String> npcNamesPlus(String extra) {
        List<String> out = npcNames();
        out.add(extra);
        return out;
    }

    private static List<String> typeList() {
        List<String> out = new ArrayList<>();
        for (EntityType t : NPCManager.ALLOWED_TYPES) out.add(t.name());
        return out;
    }

    private static List<String> professionNames() {
        List<String> out = new ArrayList<>();
        for (Villager.Profession p : Villager.Profession.values()) out.add(p.name());
        return out;
    }

    private String typeNames(String joiner) {
        return String.join(joiner, typeList());
    }

    private static EntityType matchAllowedType(String s) {
        try {
            EntityType t = EntityType.valueOf(s.toUpperCase(Locale.ROOT));
            for (EntityType allowed : NPCManager.ALLOWED_TYPES) if (allowed == t) return t;
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    private boolean perm(CommandSender s, String p) {
        if (s.hasPermission(p)) return true;
        s.sendMessage(plugin.getConfigManager().msg("no-perm"));
        return false;
    }

    private boolean needPlayer(CommandSender s) {
        if (s instanceof Player) return true;
        s.sendMessage(plugin.getConfigManager().msg("players-only"));
        return false;
    }

    private NPCData npcArg(CommandSender s, String name) {
        NPCData d = plugin.getNpcManager().findByName(name);
        if (d == null) s.sendMessage(plugin.getConfigManager().msg("not-found", "name", name));
        return d;
    }

    private void usage(CommandSender s, String u) {
        s.sendMessage(plugin.getConfigManager().msg("usage", "usage", u));
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static String onOff(boolean b) { return b ? "\u00a7aon" : "\u00a7coff"; }

    private static String inherit(Object raw) { return raw == null ? " \u00a78(global)" : ""; }

    private static String worldName(NPCData npc) {
        Location l = npc.getLocation();
        return l == null || l.getWorld() == null ? "none" : l.getWorld().getName();
    }

    private static String locString(NPCData npc) {
        Location l = npc.getLocation();
        if (l == null) return "none";
        String w = l.getWorld() == null ? "none" : l.getWorld().getName();
        return String.format(Locale.ROOT, "%s @ %.0f, %.0f, %.0f", w, l.getX(), l.getY(), l.getZ());
    }
}

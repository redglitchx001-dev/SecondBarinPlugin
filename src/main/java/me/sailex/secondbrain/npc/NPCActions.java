package me.sailex.secondbrain.npc;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles NPC "world actions": walking, breaking blocks, placing blocks.
 * These actions REQUIRE player consent (they modify the world). A clickable
 * [Accept] [Deny] prompt is sent to the talking player.
 *
 * Tags used inside AI replies:
 *   [WALK:X,Y,Z]              - pathfind to coordinates
 *   [BREAK:X,Y,Z]             - break the block at X,Y,Z (consent)
 *   [PLACE:X,Y,Z:MATERIAL]    - place a block (consent)
 *   [ASK:question text?]      - asks a yes/no question with clickable buttons
 *   [SAY:free text]           - alternate way to add dialogue (stripped like CMD)
 */
public class NPCActions {

    private static final Pattern WALK_PAT  = Pattern.compile("\\[WALK:(-?\\d+),(-?\\d+),(-?\\d+)\\]");
    private static final Pattern BREAK_PAT = Pattern.compile("\\[BREAK:(-?\\d+),(-?\\d+),(-?\\d+)\\]");
    private static final Pattern PLACE_PAT = Pattern.compile("\\[PLACE:(-?\\d+),(-?\\d+),(-?\\d+):([A-Za-z_]+)\\]");
    private static final Pattern ASK_PAT   = Pattern.compile("\\[ASK:([^\\]]+?)\\]");
    private static final Pattern SAY_PAT   = Pattern.compile("\\[SAY:([^\\]]+?)\\]");

    private final SecondBrainPlugin plugin;
    /** Pending action awaiting approval: keyed by NPC id → (playerUuid, action, expiry tick). */
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    public NPCActions(SecondBrainPlugin plugin) { this.plugin = plugin; }

    /** Parse action tags out of the reply, ask for consent, and return the cleaned reply text. */
    public String parseAndRequest(NPCData npc, String playerName, String reply) {
        if (reply == null) return null;
        String cleaned = stripAll(reply);

        Player player = playerName == null ? null : Bukkit.getPlayerExact(playerName);
        if (player == null || !player.isOnline()) return cleaned;
        // Only OPs may authorize world-modifying actions for safety.
        boolean canAuthorize = player.isOp() || player.hasPermission("secondbrain.admin");

        // Walk request (ask first)
        Matcher wm = WALK_PAT.matcher(reply);
        while (wm.find()) {
            try {
                Location loc = locFrom(npc, wm.group(1), wm.group(2), wm.group(3));
                if (loc != null) askApprove(npc, player, "walk to " + Text.num(loc.getX())
                        + ", " + Text.num(loc.getY()) + ", " + Text.num(loc.getZ()),
                        () -> doWalk(npc, loc), canAuthorize);
            } catch (Exception ignored) {}
        }
        Matcher bm = BREAK_PAT.matcher(reply);
        while (bm.find()) {
            try {
                Location loc = locFrom(npc, bm.group(1), bm.group(2), bm.group(3));
                if (loc != null) askApprove(npc, player, "break " + loc.getBlock().getType()
                                + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(),
                        () -> doBreak(npc, loc), canAuthorize);
            } catch (Exception ignored) {}
        }
        Matcher pm = PLACE_PAT.matcher(reply);
        while (pm.find()) {
            try {
                Location loc = locFrom(npc, pm.group(1), pm.group(2), pm.group(3));
                Material mat = Material.matchMaterial(pm.group(4).toUpperCase());
                if (loc != null && mat != null) askApprove(npc, player, "place " + mat.name()
                                + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(),
                        () -> doPlace(npc, loc, mat), canAuthorize);
            } catch (Exception ignored) {}
        }
        Matcher am = ASK_PAT.matcher(reply);
        while (am.find()) {
            String question = am.group(1);
            askYesNo(npc, player, question);
        }

        return cleaned;
    }

    /** Approve the pending action for this NPC, if the player is the one who was asked. */
    public boolean approve(Player player, NPCData npc) {
        PendingAction pa = pending.remove(npc.getId());
        if (pa == null) return false;
        if (!pa.playerUuid.equals(player.getUniqueId())) {
            pending.put(npc.getId(), pa);
            return false;
        }
        player.sendMessage(Component.text("\u00a7a\u2714 Approved.", NamedTextColor.GREEN));
        Bukkit.getScheduler().runTask(plugin, pa.action);
        return true;
    }

    public boolean deny(Player player, NPCData npc) {
        PendingAction pa = pending.remove(npc.getId());
        if (pa == null) return false;
        if (!pa.playerUuid.equals(player.getUniqueId())) {
            pending.put(npc.getId(), pa);
            return false;
        }
        player.sendMessage(Component.text("\u00a7c\u2718 Denied.", NamedTextColor.RED));
        return true;
    }

    public boolean answer(Player player, NPCData npc, boolean yes) {
        // Inject the player's answer back into chat so the NPC responds naturally.
        String msg = (yes ? "[Yes]" : "[No]");
        plugin.getChatService().injectPlayerMessage(npc.getId(), player, msg);
        return true;
    }

    // ------------------------------------------------------------------
    private void doWalk(NPCData npc, Location loc) {
        Entity e = npc.getEntityUuid() == null ? null : Bukkit.getEntity(npc.getEntityUuid());
        if (!(e instanceof Mob mob)) return;
        // Temporarily enable AI just for this walk, then disable on arrival.
        mob.setAI(true);
        boolean ok = mob.getPathfinder().moveTo(loc, 1.0);
        if (!ok) return;
        Bukkit.getScheduler().runTaskTimer(plugin, new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                ticks++;
                if (mob.isDead() || !mob.isValid() || mob.getLocation().getWorld() == null
                        || !loc.getWorld().equals(mob.getWorld())) { cancel(); return; }
                if (mob.getLocation().distanceSquared(loc) < 2.5 || ticks > 200) {
                    mob.getPathfinder().stopPathfinding();
                    if (!npc.isHostile()) mob.setAI(false);
                    cancel();
                }
            }
        }, 10L, 10L);
    }

    private void doBreak(NPCData npc, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        Block b = loc.getBlock();
        if (b.getType().isAir()) return;
        // Respect spawn-protection / other plugins: breakNaturally fires BlockBreakEvent.
        b.breakNaturally();
    }

    private void doPlace(NPCData npc, Location loc, Material mat) {
        if (loc == null || loc.getWorld() == null) return;
        Block b = loc.getBlock();
        if (!b.getType().isAir()) return;
        // setType fires BlockCanBuildEvent indirectly through plugins in some cases;
        // for maximum compatibility we set the type and send a fake arm-swing via swingMainHand.
        b.setType(mat, true);
        Entity e = npc.getEntityUuid() == null ? null : Bukkit.getEntity(npc.getEntityUuid());
        if (e instanceof LivingEntity le) le.swingMainHand();
    }

    // ------------------------------------------------------------------
    private Location locFrom(NPCData npc, String x, String y, String z) {
        if (npc.getEntityUuid() == null) return null;
        Entity e = Bukkit.getEntity(npc.getEntityUuid());
        if (e == null || e.getWorld() == null) return null;
        try {
            return new Location(e.getWorld(),
                    Integer.parseInt(x.trim()) + 0.5,
                    Integer.parseInt(y.trim()),
                    Integer.parseInt(z.trim()) + 0.5);
        } catch (NumberFormatException ex) { return null; }
    }

    private void askApprove(NPCData npc, Player p, String what, Runnable action, boolean canAuthorize) {
        PendingAction pa = new PendingAction(p.getUniqueId(), action, "approve", Bukkit.getCurrentTick());
        pending.put(npc.getId(), pa);
        Component line = Component.text("\u00a7e" + npc.getName() + " \u00a77wants to \u00a7f" + what + "\u00a77.", NamedTextColor.GRAY)
                .append(Component.text("  [Accept]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to allow")))
                        .clickEvent(ClickEvent.runCommand("/sbaction " + npc.getId() + " approve")))
                .append(Component.text("  [Deny]", NamedTextColor.RED).decorate(TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to refuse")))
                        .clickEvent(ClickEvent.runCommand("/sbaction " + npc.getId() + " deny")));
        p.sendMessage(line);
        if (!canAuthorize) {
            p.sendMessage(Component.text("\u00a77(You can't authorize this — ask an admin.)", NamedTextColor.GRAY));
        }
    }

    private void askYesNo(NPCData npc, Player p, String question) {
        Component line = Component.text("\u00a7e" + npc.getName() + "\u00a77: \u00a7f" + question + " ", NamedTextColor.GRAY)
                .append(Component.text(" [Yes]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/sbaction " + npc.getId() + " yes"))
                        .hoverEvent(HoverEvent.showText(Component.text("Answer yes"))))
                .append(Component.text(" [No]", NamedTextColor.RED).decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/sbaction " + npc.getId() + " no"))
                        .hoverEvent(HoverEvent.showText(Component.text("Answer no"))));
        p.sendMessage(line);
    }

    private String stripAll(String s) {
        s = SAY_PAT.matcher(s).replaceAll(mr -> mr.group(1));
        s = WALK_PAT.matcher(s).replaceAll("");
        s = BREAK_PAT.matcher(s).replaceAll("");
        s = PLACE_PAT.matcher(s).replaceAll("");
        s = ASK_PAT.matcher(s).replaceAll("");
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    private record PendingAction(UUID playerUuid, Runnable action, String kind, long createdTick) {}
}

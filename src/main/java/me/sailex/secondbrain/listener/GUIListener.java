package me.sailex.secondbrain.listener;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.gui.ConfirmAction;
import me.sailex.secondbrain.gui.GUIManager;
import me.sailex.secondbrain.npc.NPCData;
import me.sailex.secondbrain.npc.NPCManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Routes every click inside SecondBrain GUIs. */
public class GUIListener implements Listener {

    private final SecondBrainPlugin plugin;

    public GUIListener(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        String title = plain(e.getView().title());
        if (isOurs(title) && e.getRawSlots().stream().anyMatch(s -> s < e.getView().getTopInventory().getSize())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = plain(e.getView().title());
        if (!isOurs(title)) return;

        e.setCancelled(true);
        boolean top = e.getClickedInventory() == e.getView().getTopInventory();
        if (!top) return;

        int slot = e.getSlot();
        String item = itemName(e.getCurrentItem());

        if (title.contains("SecondBrain")) mainMenu(p, slot, item);
        else if (title.contains("NPC:")) editor(p, title, slot, e.getClick());
        else if (title.contains("NPCs")) npcList(p, slot, item, e.getClick());
        else if (title.contains("Global Settings")) settings(p, slot, e.getClick());
        else if (title.contains("Are you sure")) confirmMenu(p, slot);
        else if (title.contains("Entity Type")) entityPicker(p, title, item);
        else if (title.contains("Profession")) professionPicker(p, item);
        else if (title.contains("Statistics")) statsMenu(p, slot);
        else if (title.contains("Connection")) statusMenu(p, slot);
    }

    private boolean isOurs(String title) {
        return title.contains("SecondBrain") || title.contains("NPC") || title.contains("Global Settings")
                || title.contains("Are you sure") || title.contains("Profession")
                || title.contains("Statistics") || title.contains("Connection");
    }

    // ============================================================
    //  Main menu
    // ============================================================

    private void mainMenu(Player p, int slot, String item) {
        var gm = plugin.getGuiManager();
        switch (slot) {
            case 10 -> gm.openNPCList(p);
            case 12 -> gm.beginInput(p, GUIManager.InputType.CREATE_NPC, null);
            case 14 -> {
                if (!admin(p)) return;
                gm.openSettings(p);
            }
            case 16 -> gm.openStats(p);
            case 19 -> gm.openStatus(p);
            case 21 -> {
                if (!admin(p)) return;
                plugin.getNpcManager().saveAll();
                plugin.getChatService().saveMemoriesAsync();
                p.sendMessage(plugin.getConfigManager().msg("saved"));
            }
            case 23 -> {
                if (!admin(p)) return;
                plugin.reload();
                p.sendMessage(plugin.getConfigManager().msg("reloaded"));
                gm.openMainMenu(p);
            }
            case 25 -> p.closeInventory();
            default -> {}
        }
    }

    // ============================================================
    //  NPC list
    // ============================================================

    private void npcList(Player p, int slot, String item, ClickType click) {
        var gm = plugin.getGuiManager();
        var nm = plugin.getNpcManager();

        switch (slot) {
            case 45 -> { gm.openMainMenu(p); return; }
            case 48 -> { gm.openNPCList(p, gm.getPage(p.getUniqueId()) - 1); return; }
            case 50 -> { gm.openNPCList(p, gm.getPage(p.getUniqueId()) + 1); return; }
            case 53 -> {
                if (!admin(p)) return;
                gm.beginInput(p, GUIManager.InputType.CREATE_NPC, null);
                return;
            }
            default -> {}
        }

        NPCData npc = nm.findByName(item);
        if (npc == null) return;

        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            if (!admin(p)) return;
            gm.confirm(p, "Delete NPC " + npc.getName() + "?",
                    () -> {
                        if (nm.removeByName(npc.getName())) {
                            plugin.getChatService().clearMemory(npc.getId());
                            p.sendMessage(plugin.getConfigManager().msg("removed", "name", npc.getName()));
                        }
                        gm.openNPCList(p);
                    },
                    () -> gm.openNPCList(p));
            return;
        }

        if (click.isShiftClick()) {
            if (!admin(p)) return;
            Location loc = npc.getLocation();
            if (loc == null || loc.getWorld() == null) {
                p.sendMessage(plugin.getConfigManager().msg("world-missing"));
                return;
            }
            p.closeInventory();
            p.teleport(loc);
            p.sendMessage(plugin.getConfigManager().msg("teleported", "name", npc.getName()));
            return;
        }

        if (p.hasPermission("secondbrain.admin")) {
            gm.openEditor(p, npc);
        } else {
            plugin.getChatService().setFocus(p, npc);
            p.sendMessage(plugin.getConfigManager().msg("focus-set",
                    "name", npc.getName(),
                    "seconds", String.valueOf(plugin.getConfigManager().getFocusDuration())));
            p.closeInventory();
        }
    }

    // ============================================================
    //  Editor
    // ============================================================

    private void editor(Player p, String title, int slot, ClickType click) {
        if (!admin(p)) return;
        var gm = plugin.getGuiManager();
        var nm = plugin.getNpcManager();
        var cm = plugin.getConfigManager();

        String npcName = title.replace("NPC:", "").trim();
        NPCData npc = nm.findByName(npcName);
        if (npc == null) { gm.openNPCList(p); return; }

        boolean right = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;

        switch (slot) {
            case 10 -> { // chat AI toggle
                npc.setChatEnabled(right ? null : !nm.isChatEnabled(npc));
                finishToggle(p, npc);
            }
            case 11 -> { // name-only
                npc.setNameOnly(right ? null : !nm.isNameOnly(npc));
                finishToggle(p, npc);
            }
            case 12 -> { // look at players
                npc.setLookAtPlayers(right ? null : !nm.isLookAtPlayers(npc));
                finishToggle(p, npc);
            }
            case 19 -> { // name tag
                npc.setShowName(right ? null : !nm.isShowName(npc));
                finishToggle(p, npc);
            }
            case 20 -> { // glow
                npc.setGlow(right ? null : !nm.isGlow(npc));
                finishToggle(p, npc);
            }
            case 21 -> { // baby
                npc.setBaby(right ? null : !Boolean.TRUE.equals(npc.getBabyRaw()));
                nm.saveAll();
                nm.spawnEntity(npc); // baby/adult needs a respawn
                plugin.getGuiManager().openEditor(p, npc);
            }
            case 15 -> adjustRadius(p, npc, +1);
            case 16 -> adjustRadius(p, npc, +5);
            case 23 -> adjustRadius(p, npc, -1);
            case 24 -> adjustRadius(p, npc, -5);
            case 29 -> gm.beginInput(p, GUIManager.InputType.SET_PROMPT, npc);
            case 31 -> gm.openEntityPicker(p, npc);
            case 33 -> {
                if (npc.getEntityType() == EntityType.VILLAGER) gm.openProfessionPicker(p, npc);
                else p.sendMessage(cm.msgRaw("prefix") + "\u00a77Only villagers have professions.");
            }
            case 38 -> gm.beginInput(p, GUIManager.InputType.RENAME_NPC, npc);
            case 40 -> {
                Location loc = npc.getLocation();
                if (loc == null || loc.getWorld() == null) { p.sendMessage(cm.msg("world-missing")); return; }
                p.closeInventory();
                p.teleport(loc);
                p.sendMessage(cm.msg("teleported", "name", npc.getName()));
            }
            case 42 -> {
                nm.move(npc.getName(), p.getLocation());
                p.sendMessage(cm.msg("moved", "name", npc.getName()));
                gm.openEditor(p, npc);
            }
            case 45 -> gm.openNPCList(p);
            case 49 -> gm.confirm(p, "Clear memory of " + npc.getName() + "?",
                    () -> {
                        plugin.getChatService().clearMemory(npc.getId());
                        p.sendMessage(cm.msg("memory-cleared", "name", npc.getName()));
                        gm.openEditor(p, npc);
                    },
                    () -> gm.openEditor(p, npc));
            case 53 -> gm.confirm(p, "Delete NPC " + npc.getName() + "?",
                    () -> {
                        if (nm.removeByName(npc.getName())) {
                            plugin.getChatService().clearMemory(npc.getId());
                            p.sendMessage(cm.msg("removed", "name", npc.getName()));
                        }
                        gm.openNPCList(p);
                    },
                    () -> gm.openEditor(p, npc));
            default -> {}
        }
    }

    private void finishToggle(Player p, NPCData npc) {
        var nm = plugin.getNpcManager();
        nm.saveAll();
        nm.applyVisuals(npc);
        plugin.getGuiManager().openEditor(p, npc);
    }

    private void adjustRadius(Player p, NPCData npc, int delta) {
        var nm = plugin.getNpcManager();
        double current = nm.getChatRadius(npc);
        double next = Math.max(2, Math.min(200, current + delta));
        npc.setChatRadius(next);
        nm.saveAll();
        p.sendMessage(plugin.getConfigManager().msg("radius-set", "name", npc.getName(), "radius", me.sailex.secondbrain.util.Text.num(next)));
        plugin.getGuiManager().openEditor(p, npc);
    }

    // ============================================================
    //  Global settings
    // ============================================================

    private void settings(Player p, int slot, ClickType click) {
        if (!admin(p)) return;
        var cm = plugin.getConfigManager();
        var gm = plugin.getGuiManager();

        switch (slot) {
            case 10 -> cm.setChatEnabled(!cm.isChatEnabled());
            case 11 -> cm.setRespondOnlyToName(!cm.isRespondOnlyToName());
            case 12 -> cm.setLookAtPlayers(!cm.isLookAtPlayers());
            case 13 -> {
                cm.setShowName(!cm.isShowName());
                plugin.getNpcManager().refreshAllVisuals();
            }
            case 14 -> {
                cm.setGlow(!cm.isGlow());
                plugin.getNpcManager().refreshAllVisuals();
            }
            case 15 -> cm.setTypingIndicator(!cm.isTypingIndicator());
            case 16 -> cm.setDebug(!cm.isDebug());
            case 21 -> { gm.beginInput(p, GUIManager.InputType.SET_KEY, null); return; }
            case 23 -> { gm.beginInput(p, GUIManager.InputType.SET_URL, null); return; }
            case 25 -> { gm.beginInput(p, GUIManager.InputType.SET_MODEL, null); return; }
            case 40 -> { gm.openMainMenu(p); return; }
            default -> {}
        }
        if (slot >= 10 && slot <= 16) {
            String setting = switch (slot) {
                case 10 -> "chat"; case 11 -> "nameonly"; case 12 -> "look";
                case 13 -> "nametags"; case 14 -> "glow"; case 15 -> "typing"; case 16 -> "debug";
                default -> "?";
            };
            boolean value = switch (slot) {
                case 10 -> cm.isChatEnabled(); case 11 -> cm.isRespondOnlyToName(); case 12 -> cm.isLookAtPlayers();
                case 13 -> cm.isShowName(); case 14 -> cm.isGlow(); case 15 -> cm.isTypingIndicator(); case 16 -> cm.isDebug();
                default -> false;
            };
            p.sendMessage(cm.msgRaw("toggle-set", "setting", setting, "value", value ? "\u00a7aon" : "\u00a7coff"));
        }
        gm.openSettings(p);
    }

    // ============================================================
    //  Confirm / pickers / stats / status
    // ============================================================

    private void confirmMenu(Player p, int slot) {
        if (slot != 11 && slot != 15) return;
        ConfirmAction action = plugin.getGuiManager().takeConfirm(p);
        if (action == null) { p.closeInventory(); return; }
        if (slot == 11 && action.getOnConfirm() != null) action.getOnConfirm().run();
        else if (slot == 15) {
            if (action.getOnCancel() != null) action.getOnCancel().run();
            else p.closeInventory();
        }
    }

    private void entityPicker(Player p, String title, String item) {
        var gm = plugin.getGuiManager();
        var nm = plugin.getNpcManager();
        NPCData npc = gm.peekPickerTarget(p);

        if (item.equals("Back")) {
            gm.takePickerTarget(p);
            if (npc != null) gm.openEditor(p, npc); else gm.openMainMenu(p);
            return;
        }

        EntityType type;
        try { type = EntityType.valueOf(item.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return; } // filler click: keep target
        if (npc == null) { p.closeInventory(); return; }
        gm.takePickerTarget(p);

        npc.setEntityType(type);
        if (type != EntityType.VILLAGER) npc.setProfession(null);
        nm.spawnEntity(npc);
        nm.saveAll();
        p.sendMessage(plugin.getConfigManager().msg("type-set", "name", npc.getName(), "type", type.name()));
        gm.openEditor(p, npc);
    }

    private void professionPicker(Player p, String item) {
        var gm = plugin.getGuiManager();
        NPCData npc = gm.peekPickerTarget(p);

        if (item.equals("Back")) {
            gm.takePickerTarget(p);
            if (npc != null) gm.openEditor(p, npc); else gm.openMainMenu(p);
            return;
        }

        Villager.Profession prof;
        try { prof = Villager.Profession.valueOf(item.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return; } // filler click: keep target
        if (npc == null) { p.closeInventory(); return; }
        gm.takePickerTarget(p);

        npc.setProfession(prof == Villager.Profession.NONE ? null : prof);
        plugin.getNpcManager().spawnEntity(npc);
        plugin.getNpcManager().saveAll();
        p.sendMessage(plugin.getConfigManager().msg("profession-set", "name", npc.getName(), "profession", prof.name()));
        gm.openEditor(p, npc);
    }

    private void statsMenu(Player p, int slot) {
        if (slot == 16) plugin.getGuiManager().openStatus(p);
        else if (slot == 22) plugin.getGuiManager().openMainMenu(p);
    }

    private void statusMenu(Player p, int slot) {
        var gm = plugin.getGuiManager();
        if (slot == 22) { gm.openMainMenu(p); return; }
        if (slot != 16) return;

        p.sendMessage(plugin.getConfigManager().msgRaw("prefix") + "\u00a77Testing connection...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String error = plugin.getLlmClient().testConnection();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (error == null) {
                    p.sendMessage(plugin.getConfigManager().msgRaw("prefix") + "\u00a7aConnection OK! The AI answered the ping.");
                } else {
                    p.sendMessage(plugin.getConfigManager().msgRaw("prefix") + "\u00a7cConnection failed: \u00a7e" + error);
                }
            });
        });
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private boolean admin(Player p) {
        if (p.hasPermission("secondbrain.admin")) return true;
        p.sendMessage(plugin.getConfigManager().msg("no-perm"));
        return false;
    }

    private String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private String itemName(ItemStack i) {
        if (i == null || !i.hasItemMeta() || i.getItemMeta() == null || i.getItemMeta().displayName() == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(i.getItemMeta().displayName()).trim();
    }
}

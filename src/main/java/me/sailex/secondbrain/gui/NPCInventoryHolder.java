package me.sailex.secondbrain.gui;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Stamps NPC items with a persistent id so GUI clicks can find the NPC
 * without parsing display names (which breaks with color codes).
 */
public final class NPCInventoryHolder {
    private NPCInventoryHolder() {}

    /** Lazy so we don't construct during class init before the plugin exists. */
    private static NamespacedKey KEY;

    public static NamespacedKey key() {
        if (KEY == null) KEY = new NamespacedKey(SecondBrainPlugin.getInstance(), "npc-id");
        return KEY;
    }

    public static ItemStack stamp(ItemStack item, NPCData npc) {
        if (item == null || npc == null) return item;
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, npc.getId());
            item.setItemMeta(meta);
        }
        return item;
    }
}

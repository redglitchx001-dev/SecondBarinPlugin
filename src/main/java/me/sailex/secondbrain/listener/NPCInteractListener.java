package me.sailex.secondbrain.listener;

import me.sailex.secondbrain.SecondBrainPlugin;
import me.sailex.secondbrain.npc.NPCData;
import me.sailex.secondbrain.npc.NPCManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Right-click an NPC: focus it (players) or open the editor (sneaking admins). */
public class NPCInteractListener implements Listener {

    private final SecondBrainPlugin plugin;

    public NPCInteractListener(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!clicked.getScoreboardTags().contains(NPCManager.ENTITY_TAG)) return;

        NPCData npc = plugin.getNpcManager().findByEntity(clicked.getUniqueId());
        if (npc == null) return;
        event.setCancelled(true);

        Player player = event.getPlayer();

        if (player.isSneaking() && player.hasPermission("secondbrain.admin")) {
            plugin.getGuiManager().openEditor(player, npc);
            return;
        }

        if (!player.hasPermission("secondbrain.use")) return;

        plugin.getChatService().setFocus(player, npc);
        int seconds = plugin.getConfigManager().getFocusDuration();
        String line = plugin.getConfigManager().msgRaw("focus-set", "name", npc.getName(), "seconds", String.valueOf(seconds));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(line));
        player.sendMessage(plugin.getConfigManager().msg("focus-set", "name", npc.getName(), "seconds", String.valueOf(seconds)));
    }
}

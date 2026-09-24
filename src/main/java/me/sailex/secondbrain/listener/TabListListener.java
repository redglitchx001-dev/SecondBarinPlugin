package me.sailex.secondbrain.listener;

import me.sailex.secondbrain.SecondBrainPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Pushes the fancy tab header/footer to new players immediately on join. */
public class TabListListener implements Listener {

    private final SecondBrainPlugin plugin;
    public TabListListener(SecondBrainPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.pushTabList(e.getPlayer()), 5L);
    }
}


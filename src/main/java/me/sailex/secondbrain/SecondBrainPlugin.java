package me.sailex.secondbrain;

import me.sailex.secondbrain.chat.ChatService;
import me.sailex.secondbrain.command.ActionCommand;
import me.sailex.secondbrain.command.CommandExecutor;
import me.sailex.secondbrain.command.SecondBrainCommand;
import me.sailex.secondbrain.config.ConfigManager;
import me.sailex.secondbrain.gui.GUIManager;
import me.sailex.secondbrain.listener.ChatListener;
import me.sailex.secondbrain.listener.GUIListener;
import me.sailex.secondbrain.listener.NPCInteractListener;
import me.sailex.secondbrain.listener.NPCProtectionListener;
import me.sailex.secondbrain.listener.TabListListener;
import me.sailex.secondbrain.llm.LLMClient;
import me.sailex.secondbrain.npc.NPCActions;
import me.sailex.secondbrain.npc.NPCCombat;
import me.sailex.secondbrain.npc.NPCManager;
import me.sailex.secondbrain.skin.SkinManager;
import me.sailex.secondbrain.stats.Stats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SecondBrain v5 - AI-powered NPCs for Paper 1.21.x.
 * Zero dependencies: no Citizens, no ProtocolLib, no NMS.
 */
public class SecondBrainPlugin extends JavaPlugin {

    private static SecondBrainPlugin instance;

    private ConfigManager configManager;
    private Stats stats;
    private LLMClient llmClient;
    private SkinManager skinManager;
    private NPCManager npcManager;
    private NPCCombat npcCombat;
    private NPCActions npcActions;
    private CommandExecutor commandExecutor;
    private ChatService chatService;
    private GUIManager guiManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        stats = new Stats();
        llmClient = new LLMClient(this);
        skinManager = new SkinManager(this);
        commandExecutor = new CommandExecutor(this);
        npcActions = new NPCActions(this);
        npcManager = new NPCManager(this);
        npcCombat = new NPCCombat(this);
        chatService = new ChatService(this);
        guiManager = new GUIManager(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new NPCInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new TabListListener(this), this);
        NPCProtectionListener protection = new NPCProtectionListener(this);
        getServer().getPluginManager().registerEvents(protection, this);
        protection.startAnchorTask();
        startTabListTask();

        SecondBrainCommand cmd = new SecondBrainCommand(this);
        PluginCommand sb = getCommand("secondbrain");
        if (sb != null) { sb.setExecutor(cmd); sb.setTabCompleter(cmd); }
        ActionCommand ac = new ActionCommand(this);
        PluginCommand sba = getCommand("sbaction");
        if (sba != null) { sba.setExecutor(ac); sba.setTabCompleter(ac); }

        if (!configManager.isKeyConfigured()) {
            getLogger().warning("No API key configured yet. Use /sb setkey <key> or edit config.yml.");
        }
        getLogger().info("SecondBrain v" + getPluginMeta().getVersion()
                + " enabled - " + npcManager.getAllNPCs().size() + " NPC(s), zero dependencies.");
    }

    @Override
    public void onDisable() {
        if (chatService != null) chatService.saveMemoriesNow();
        if (npcManager != null) npcManager.saveAll();
        if (llmClient != null) llmClient.shutdown();
        if (skinManager != null) skinManager.shutdown();
    }

    /** Reloads config.yml and re-applies visuals. */
    public void reload() {
        reloadConfig();
        configManager.reload();
        llmClient.reload();
        if (npcManager != null) npcManager.refreshAllVisuals();
    }

    public static SecondBrainPlugin getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public Stats getStats() { return stats; }
    public LLMClient getLlmClient() { return llmClient; }
    public SkinManager getSkinManager() { return skinManager; }
    public NPCManager getNpcManager() { return npcManager; }
    public NPCCombat getNpcCombat() { return npcCombat; }
    public NPCActions getNpcActions() { return npcActions; }
    public CommandExecutor getCommandExecutor() { return commandExecutor; }
    public ChatService getChatService() { return chatService; }
    public GUIManager getGuiManager() { return guiManager; }

    // -----------------------------------------------------------------
    //  Tab list: fancy header/footer showing players + NPC count.
    //  (Adding fake player-name entries requires NMS/protocol-lib; that's
    //   forbidden by our zero-dep rule so we use the header/footer API.)
    // -----------------------------------------------------------------
    private void startTabListTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) pushTabList(p);
        }, 20L, 40L);
    }

    public void pushTabList(Player p) {
        int players = Bukkit.getOnlinePlayers().size();
        int npcs = npcManager.getAllNPCs().size();
        int hostile = 0, chatty = 0;
        for (var n : npcManager.getAllNPCs().values()) {
            if (n.isHostile()) hostile++;
            if (npcManager.isChatEnabled(n)) chatty++;
        }
        Component header = MiniMessage.miniMessage().deserialize(
                "<gradient:#55cdfc:#ff6ec7><bold>  \u2726 SecondBrain \u2726  </bold></gradient>\n"
              + "<gray>AI-powered NPCs \u2014 v" + getPluginMeta().getVersion() + "\n"
              + "<gray><italic>say an NPC's name in chat to talk");
        Component footer = MiniMessage.miniMessage().deserialize(
                "\n<aqua>\u25cf Players: <white>" + players
              + "  <green>\u25cf NPCs: <white>" + npcs
              + "  <yellow>\u25cf Chatty: <white>" + chatty
              + "  <red>\u25cf Hostile: <white>" + hostile
              + "\n<gray>/sb \u2022 /sb near \u2022 /sb gui");
        p.sendPlayerListHeaderAndFooter(header, footer);
    }
}

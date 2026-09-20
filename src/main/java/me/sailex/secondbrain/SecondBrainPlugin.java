package me.sailex.secondbrain;

import me.sailex.secondbrain.chat.ChatService;
import me.sailex.secondbrain.command.SecondBrainCommand;
import me.sailex.secondbrain.config.ConfigManager;
import me.sailex.secondbrain.gui.GUIManager;
import me.sailex.secondbrain.listener.ChatListener;
import me.sailex.secondbrain.listener.GUIListener;
import me.sailex.secondbrain.listener.NPCInteractListener;
import me.sailex.secondbrain.llm.LLMClient;
import me.sailex.secondbrain.npc.NPCManager;
import me.sailex.secondbrain.stats.Stats;
import org.bukkit.command.PluginCommand;
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
    private NPCManager npcManager;
    private ChatService chatService;
    private GUIManager guiManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        stats = new Stats();
        llmClient = new LLMClient(this);
        npcManager = new NPCManager(this);
        chatService = new ChatService(this);
        guiManager = new GUIManager(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new NPCInteractListener(this), this);

        SecondBrainCommand cmd = new SecondBrainCommand(this);
        PluginCommand command = getCommand("secondbrain");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

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
    public NPCManager getNpcManager() { return npcManager; }
    public ChatService getChatService() { return chatService; }
    public GUIManager getGuiManager() { return guiManager; }
}

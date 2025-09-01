package dev.charlielarson.ai_chat;

import dev.charlielarson.ai_chat.command.AiCommand;
import dev.charlielarson.ai_chat.config.ModConfig;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.*;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class FabricAiChatMod implements DedicatedServerModInitializer {
    public static final String MODID = "ai_chat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ModConfig CONFIG = ModConfig.defaultConfig();
    private static MinecraftServer SERVER;

    public static ModConfig getConfig() {
        return CONFIG;
    }

    public static MinecraftServer getServer() {
        return SERVER;
    }

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            SERVER = server;
            loadOrCreateConfig(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AiCommand.register(dispatcher);
        });

        LOGGER.info("AI Chat initialized.");
    }

    public static synchronized void reloadConfig() {
        if (SERVER == null)
            return;
        loadOrCreateConfig(SERVER);
    }

    private static void loadOrCreateConfig(MinecraftServer server) {
        try {
            File cfgDir = server.getRunDirectory().resolve("config").toFile();
            if (!cfgDir.exists())
                cfgDir.mkdirs();
            File cfgFile = new File(cfgDir, "ai-chat.json");
            if (!cfgFile.exists()) {
                CONFIG = ModConfig.defaultConfig();
                try (FileWriter w = new FileWriter(cfgFile)) {
                    GSON.toJson(CONFIG, w);
                }
                LOGGER.info("Created default config at {}", cfgFile.getAbsolutePath());
            } else {
                try (FileReader r = new FileReader(cfgFile)) {
                    CONFIG = GSON.fromJson(r, ModConfig.class);
                }
                if (CONFIG == null)
                    CONFIG = ModConfig.defaultConfig();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
            CONFIG = ModConfig.defaultConfig();
        }
    }
}
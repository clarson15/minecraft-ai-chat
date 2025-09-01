package dev.charlielarson.ai_chat.config;

import java.util.List;

public class ModConfig {
    public String provider; // "openai" or "ollama"

    // OpenAI
    public String openaiApiBase; // e.g. "https://api.openai.com/v1"
    public String openaiApiKey; // OPTIONAL: if empty, read from env OPENAI_API_KEY
    public String openaiModel; // e.g. "gpt-4o-mini" or "gpt-4o"

    // Ollama
    public String ollamaBaseUrl; // e.g. "http://localhost:11434"
    public String ollamaModel; // e.g. "llama3.1:8b"

    // Behavior
    public String systemPrompt; // server instructions
    public boolean allowRunCommands; // if true, model may request server commands
    public List<String> commandAllowlist; // optional allowlist of command prefixes, e.g. ["say", "time set", "give"]
    public int cooldownSeconds; // per-player rate limit
    public int maxHistory; // number of recent exchanges to keep per player
    public double temperature; // sampling temperature
    public int maxTokens; // max tokens for completion (if supported)

    // HTTP timeouts (seconds). If <= 0, provider will use built-in defaults.
    public int httpConnectTimeoutSec; // TCP connect timeout
    public int httpReadTimeoutSec; // socket read timeout
    public int httpWriteTimeoutSec; // socket write timeout
    public int httpCallTimeoutSec; // total call deadline; 0 = no limit

    public static ModConfig defaultConfig() {
        ModConfig c = new ModConfig();
        c.provider = "openai";
        c.openaiApiBase = "https://api.openai.com/v1";
        c.openaiApiKey = ""; // read from env if empty
        c.openaiModel = "gpt-4o-mini";
        c.ollamaBaseUrl = "http://localhost:11434";
        c.ollamaModel = "llama3.1:8b";
        c.systemPrompt = "You are the helpful assistant of this Minecraft server. Assume all requests are related to the video game Minecraft: Java Edition. If a request requires running a server command, use the included tool `run_command`. Otherwise, answer their request normally. Keep answers short and avoid markdown by keeping it conversational.";
        c.allowRunCommands = false;
        c.commandAllowlist = List.of();
        c.cooldownSeconds = 5;
        c.maxHistory = 10;
        c.temperature = 0.4;
        c.maxTokens = 512;
        // Defaults tuned for on-LAN Ollama; increase if your model is slow
        c.httpConnectTimeoutSec = 10;
        c.httpReadTimeoutSec = 120;
        c.httpWriteTimeoutSec = 120;
        c.httpCallTimeoutSec = 300;
        return c;
    }
}
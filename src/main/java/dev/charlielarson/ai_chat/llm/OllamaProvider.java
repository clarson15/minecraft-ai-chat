package dev.charlielarson.ai_chat.llm;

import com.google.gson.*;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class OllamaProvider implements LlmProvider {
    private final String baseUrl;
    private final String model;
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient http;
    // Whether we should advertise/parse tools at all (driven by config)
    private final boolean allowTools;

    public OllamaProvider(String baseUrl, String model) {
        this(baseUrl, model, null);
    }

    public OllamaProvider(String baseUrl, String model, dev.charlielarson.ai_chat.config.ModConfig cfg) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        OkHttpClient.Builder b = new OkHttpClient.Builder();
        // Apply timeouts if provided via config; use sensible defaults otherwise
        int connect = cfg != null ? cfg.httpConnectTimeoutSec : 10;
        int read = cfg != null ? cfg.httpReadTimeoutSec : 120;
        int write = cfg != null ? cfg.httpWriteTimeoutSec : 120;
        int call = cfg != null ? cfg.httpCallTimeoutSec : 300;
        if (connect > 0)
            b.connectTimeout(connect, TimeUnit.SECONDS);
        if (read > 0)
            b.readTimeout(read, TimeUnit.SECONDS);
        if (write > 0)
            b.writeTimeout(write, TimeUnit.SECONDS);
        // 0 means no deadline
        if (call > 0)
            b.callTimeout(call, TimeUnit.SECONDS);
        this.http = b.build();
        this.allowTools = cfg != null && cfg.allowRunCommands;
    }

    @Override
    public Result chat(List<ChatMessage> messages, double temperature, int maxTokens) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", false);
        // Provide tools only when allowed; otherwise explicitly opt out
        if (allowTools) {
            // Provide tools so models that support function calling can trigger them
            // natively
            JsonArray tools = buildToolsJson();
            if (tools != null && tools.size() > 0) {
                root.add("tools", tools);
            }
            // Hint that tool use is optional/automatic, not forced
            root.addProperty("tool_choice", "auto");
        } else {
            // Most Ollama builds ignore unknown fields; if supported, this disables tool
            // calls
            root.addProperty("tool_choice", "none");
        }
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject jm = new JsonObject();
            jm.addProperty("role", m.role());
            jm.addProperty("content", m.content());
            msgs.add(jm);
        }
        root.add("messages", msgs);
        // Encode options as an object
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", temperature);
        if (maxTokens > 0)
            opts.addProperty("num_predict", maxTokens);
        root.add("options", opts);

        Request req = new Request.Builder()
                .url(baseUrl + "/api/chat")
                .post(RequestBody.create(root.toString().getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful())
                throw new RuntimeException("Ollama error: " + resp.code() + " " + resp.message());
            String body = resp.body().string();
            // Lightweight debug in server log for troubleshooting
            dev.charlielarson.ai_chat.FabricAiChatMod.LOGGER.info("Ollama raw response: {}",
                    body);

            JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
            String text = null;
            // Primary shape from /api/chat: { message: { content: "..." } }
            if (jo.has("message") && jo.get("message").isJsonObject()) {
                JsonObject msg = jo.getAsJsonObject("message");
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    text = msg.get("content").getAsString();
                }
            }
            // Fallback: some responses use 'response' field
            if ((text == null || text.isBlank()) && jo.has("response") && !jo.get("response").isJsonNull()) {
                text = jo.get("response").getAsString();
            }
            if (text == null)
                text = "";
            // Only parse tool calls when tools are allowed; otherwise ignore them
            ToolCall tool = null;
            if (allowTools) {
                // Prefer structured tool_calls when present; otherwise fall back to fenced JSON
                // in text
                tool = tryParseOllamaToolCalls(jo);
                if (tool == null) {
                    tool = tryParseTool(text);
                }
                if (tool == null && jo != null && ((jo.has("tool_calls") && jo.get("tool_calls").isJsonArray())
                        || (jo.has("message") && jo.get("message").getAsJsonObject().has("tool_calls")))) {
                    dev.charlielarson.ai_chat.FabricAiChatMod.LOGGER
                            .debug("Ollama tool_calls present but not parsed.");
                }
            }
            return new Result(text, tool);
        }
    }

    private JsonArray buildToolsJson() {
        try {
            JsonArray tools = new JsonArray();
            tools.add(buildFunctionDef("run_command"));
            return tools;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject buildFunctionDef(String name) {
        JsonObject fn = new JsonObject();
        fn.addProperty("type", "function");
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("description",
                "Execute a Minecraft server command. Use sparingly and only when an action requires a /command.");
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "string");
        cmd.addProperty("description", "The server command to run, with or without a leading slash.");
        props.add("command", cmd);
        params.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("command");
        params.add("required", req);
        params.addProperty("additionalProperties", false);
        f.add("parameters", params);
        fn.add("function", f);
        return fn;
    }

    /**
     * Parse Ollama's structured tool/function calls when present, e.g.
     * {
     * "message": {
     * "tool_calls": [
     * {"function": {"name": "repo.run_command", "arguments": {"tool":"run_command",
     * "command":"kill @s"}}}
     * ]
     * }
     * }
     */
    private ToolCall tryParseOllamaToolCalls(JsonObject root) {
        try {
            if (root == null || !root.has("message") || !root.get("message").isJsonObject())
                return null;
            JsonObject msg = root.getAsJsonObject("message");
            JsonArray calls = null;
            if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
                calls = msg.getAsJsonArray("tool_calls");
            } else if (root.has("tool_calls") && root.get("tool_calls").isJsonArray()) {
                // Some implementations put tool_calls at the top level
                calls = root.getAsJsonArray("tool_calls");
            }
            if (calls == null)
                return null;
            if (calls.size() == 0)
                return null;

            JsonObject first = calls.get(0).getAsJsonObject();
            if (!first.has("function") || !first.get("function").isJsonObject())
                return null;
            JsonObject fn = first.getAsJsonObject("function");
            // function name is optional for us; arguments carry the intent
            // (e.g., {"tool":"run_command","command":"..."})

            // Arguments can be an object or a JSON string; handle both
            String tool = null;
            String command = null;
            if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                JsonElement argsEl = fn.get("arguments");
                JsonObject argsObj = null;
                if (argsEl.isJsonObject()) {
                    argsObj = argsEl.getAsJsonObject();
                } else if (argsEl.isJsonPrimitive()) {
                    try {
                        argsObj = JsonParser.parseString(argsEl.getAsString()).getAsJsonObject();
                    } catch (Exception ignored) {
                    }
                }
                if (argsObj != null) {
                    if (argsObj.has("tool") && !argsObj.get("tool").isJsonNull()) {
                        tool = argsObj.get("tool").getAsString();
                    }
                    if (argsObj.has("command") && !argsObj.get("command").isJsonNull()) {
                        command = argsObj.get("command").getAsString();
                    } else if (argsObj.has("cmd") && !argsObj.get("cmd").isJsonNull()) {
                        command = argsObj.get("cmd").getAsString();
                    }
                }
            }

            // Infer tool if not explicitly provided but a command is present
            if ((tool == null || tool.isBlank()) && command != null && !command.isBlank()) {
                tool = "run_command";
            }
            if (tool != null && !tool.isBlank()) {
                return new ToolCall(tool, command);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ToolCall tryParseTool(String text) {
        int start = text.indexOf("{\"tool\"");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            String json = text.substring(start, end + 1);
            try {
                JsonObject t = JsonParser.parseString(json).getAsJsonObject();
                // Only accept the specific tool we support and require a non-empty command
                if (t.has("tool") && !t.get("tool").isJsonNull() &&
                        "run_command".equalsIgnoreCase(t.get("tool").getAsString()) &&
                        t.has("command") && !t.get("command").isJsonNull() &&
                        !t.get("command").getAsString().isBlank()) {
                    return new ToolCall("run_command", t.get("command").getAsString());
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
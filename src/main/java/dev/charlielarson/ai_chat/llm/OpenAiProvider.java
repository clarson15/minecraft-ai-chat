package dev.charlielarson.ai_chat.llm;

import com.google.gson.*;
import okhttp3.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenAiProvider implements LlmProvider {
    private final String apiBase;
    private final String apiKey;
    private final String model;
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient http;
    private final boolean allowTools;

    public OpenAiProvider(String apiBase, String apiKey, String model) {
        this(apiBase, apiKey, model, null);
    }

    public OpenAiProvider(String apiBase, String apiKey, String model, dev.charlielarson.ai_chat.config.ModConfig cfg) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.apiKey = apiKey != null && !apiKey.isBlank() ? apiKey : System.getenv("OPENAI_API_KEY");
        this.model = model;
        OkHttpClient.Builder b = new OkHttpClient.Builder();
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
        if (call > 0)
            b.callTimeout(call, TimeUnit.SECONDS);
        this.http = b.build();
        this.allowTools = cfg != null && cfg.allowRunCommands;
    }

    @Override
    public Result chat(List<ChatMessage> messages, double temperature, int maxTokens) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", temperature);
        if (maxTokens > 0)
            root.addProperty("max_tokens", maxTokens);
        // Advertise tools only when allowed; otherwise explicitly disable
        if (allowTools) {
            JsonArray tools = buildToolsJson();
            if (tools != null && tools.size() > 0) {
                root.add("tools", tools);
            }
            root.addProperty("tool_choice", "auto");
        } else {
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

        Request req = new Request.Builder()
                .url(apiBase + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(root.toString().getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful())
                throw new RuntimeException("OpenAI error: " + resp.code() + " " + resp.message());
            String body = resp.body().string();
            dev.charlielarson.ai_chat.FabricAiChatMod.LOGGER.info("OpenAI raw response: {}",
                    body.length() > 500 ? body.substring(0, 500) + "…" : body);
            JsonObject jo = JsonParser.parseString(body).getAsJsonObject();
            JsonObject choice = jo.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject msg = choice.getAsJsonObject("message");
            String text = "";
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                try {
                    text = msg.get("content").getAsString();
                } catch (Exception ignored) {
                }
            }
            // Prefer structured tool calls when present, but only when tools are allowed
            ToolCall tool = null;
            if (allowTools) {
                tool = tryParseOpenAiToolCalls(msg);
                if (tool == null) {
                    tool = tryParseOpenAiFunctionCall(msg);
                }
                if (tool == null) {
                    tool = tryParseTool(text);
                }
                if (tool == null && msg != null) {
                    if (msg.has("tool_calls")) {
                        dev.charlielarson.ai_chat.FabricAiChatMod.LOGGER
                                .debug("OpenAI tool_calls present but not parsed.");
                    }
                    if (msg.has("function_call")) {
                        dev.charlielarson.ai_chat.FabricAiChatMod.LOGGER
                                .debug("OpenAI function_call present but not parsed.");
                    }
                }
            }
            return new Result(text, tool);
        }
    }

    private JsonArray buildToolsJson() {
        try {
            JsonArray tools = new JsonArray();
            // Primary function name
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
                "Execute a Minecraft server command. Use only when an in-game action requires a /command.");
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
     * Parse OpenAI chat.completions tool_calls structure.
     */
    private ToolCall tryParseOpenAiToolCalls(JsonObject message) {
        try {
            if (message == null || !message.has("tool_calls") || !message.get("tool_calls").isJsonArray())
                return null;
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls.size() == 0)
                return null;
            JsonObject first = calls.get(0).getAsJsonObject();
            if (!first.has("function") || !first.get("function").isJsonObject())
                return null;
            JsonObject fn = first.getAsJsonObject("function");
            String name = fn.has("name") && !fn.get("name").isJsonNull() ? fn.get("name").getAsString() : "";

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
                    if (argsObj.has("command") && !argsObj.get("command").isJsonNull()) {
                        command = argsObj.get("command").getAsString();
                    } else if (argsObj.has("cmd") && !argsObj.get("cmd").isJsonNull()) {
                        command = argsObj.get("cmd").getAsString();
                    }
                }
            }

            // Normalize tool name from function name or arguments
            String toolName = null;
            String lname = name != null ? name.toLowerCase() : "";
            if (lname.equals("run_command") || lname.endsWith(".run_command") || lname.contains("run_command")) {
                toolName = "run_command";
            }
            // If the function name wasn't clear, infer from presence of a command argument
            if (toolName == null && command != null && !command.isBlank()) {
                toolName = "run_command";
            }
            if (toolName != null) {
                return new ToolCall(toolName, command);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Parse legacy OpenAI function_call format on the assistant message.
     */
    private ToolCall tryParseOpenAiFunctionCall(JsonObject message) {
        try {
            if (message == null || !message.has("function_call") || !message.get("function_call").isJsonObject())
                return null;
            JsonObject fc = message.getAsJsonObject("function_call");
            String name = fc.has("name") && !fc.get("name").isJsonNull() ? fc.get("name").getAsString() : "";
            String argsStr = null;
            if (fc.has("arguments") && !fc.get("arguments").isJsonNull()) {
                if (fc.get("arguments").isJsonPrimitive()) {
                    argsStr = fc.get("arguments").getAsString();
                } else {
                    argsStr = fc.get("arguments").toString();
                }
            }
            String command = null;
            if (argsStr != null && !argsStr.isBlank()) {
                try {
                    JsonObject args = JsonParser.parseString(argsStr).getAsJsonObject();
                    if (args.has("command") && !args.get("command").isJsonNull()) {
                        command = args.get("command").getAsString();
                    } else if (args.has("cmd") && !args.get("cmd").isJsonNull()) {
                        command = args.get("cmd").getAsString();
                    }
                } catch (Exception ignored) {
                }
            }
            String lname = name != null ? name.toLowerCase() : "";
            if ((lname.contains("run_command") || (command != null && !command.isBlank()))) {
                return new ToolCall("run_command", command);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ToolCall tryParseTool(String text) {
        // Look for fenced JSON block and only accept the one supported tool with
        // non-empty command
        int start = text.indexOf("{\"tool\"");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            String json = text.substring(start, end + 1);
            try {
                JsonObject t = JsonParser.parseString(json).getAsJsonObject();
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
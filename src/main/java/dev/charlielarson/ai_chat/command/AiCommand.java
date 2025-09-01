package dev.charlielarson.ai_chat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.charlielarson.ai_chat.FabricAiChatMod;
import dev.charlielarson.ai_chat.config.ModConfig;
import dev.charlielarson.ai_chat.llm.*;
import dev.charlielarson.ai_chat.util.RateLimiter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.*;

public class AiCommand {
    private static final Map<UUID, Deque<ChatMessage>> history = new ConcurrentHashMap<>();
    private static RateLimiter limiter;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        ModConfig cfg = FabricAiChatMod.getConfig();
        if (cfg == null)
            cfg = ModConfig.defaultConfig();
        limiter = new RateLimiter(Math.max(0, cfg.cooldownSeconds));

        dispatcher.register(CommandManager.literal("ai")
                .then(CommandManager.literal("ask")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String msg = StringArgumentType.getString(ctx, "message");
                                    ServerCommandSource src = ctx.getSource();
                                    ask(src, msg);
                                    return 1;
                                })))
                .then(CommandManager.literal("reset").executes(ctx -> {
                    UUID id = getSenderId(ctx.getSource());
                    history.remove(id);
                    ctx.getSource().sendFeedback(() -> Text.literal("AI history cleared."), false);
                    return 1;
                }))
                .then(CommandManager.literal("reload").requires(s -> s.hasPermissionLevel(3))
                        .executes(ctx -> {
                            FabricAiChatMod.reloadConfig();
                            limiter = new RateLimiter(FabricAiChatMod.getConfig().cooldownSeconds);
                            ctx.getSource().sendFeedback(() -> Text.literal("Fabric AI Chat config reloaded."), true);
                            return 1;
                        })));
    }

    private static void ask(ServerCommandSource src, String userMsg) {
        // Ensure limiter/config exist
        ModConfig cfg = FabricAiChatMod.getConfig();
        if (cfg == null)
            cfg = ModConfig.defaultConfig();
        if (limiter == null)
            limiter = new RateLimiter(Math.max(0, cfg.cooldownSeconds));

        UUID id = getSenderId(src);
        if (!limiter.tryAcquire(id)) {
            long remain = limiter.remaining(id);
            src.sendError(Text.literal("You're talking too fast. Try again in " + remain + "s."));
            return;
        }

        src.sendFeedback(() -> Text.literal("§7[AI] Thinking…"), false);

        // Build conversation
        final ModConfig cfgFinal = cfg;
        Deque<ChatMessage> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", cfg.systemPrompt));
        for (ChatMessage m : h)
            messages.add(m);
        messages.add(new ChatMessage("user", userMsg));

        // Choose provider
        LlmProvider provider;
        try {
            if ("ollama".equalsIgnoreCase(cfgFinal.provider)) {
                provider = new OllamaProvider(cfgFinal.ollamaBaseUrl, cfgFinal.ollamaModel, cfgFinal);
                FabricAiChatMod.LOGGER.debug("AI provider=ollama baseUrl={} model={}", cfgFinal.ollamaBaseUrl,
                        cfgFinal.ollamaModel);
            } else {
                provider = new OpenAiProvider(cfgFinal.openaiApiBase, cfgFinal.openaiApiKey, cfgFinal.openaiModel,
                        cfgFinal);
                FabricAiChatMod.LOGGER.debug("AI provider=openai apiBase={} model={}", cfgFinal.openaiApiBase,
                        cfgFinal.openaiModel);
            }
        } catch (Exception e) {
            src.sendError(Text.literal("AI provider error: " + e.getMessage()));
            return;
        }

        // Call model off-thread to avoid blocking server tick
        CompletableFuture.runAsync(() -> {
            try {
                LlmProvider.Result res = provider.chat(messages, cfgFinal.temperature, cfgFinal.maxTokens);
                String reply = res.text();
                ToolCall tool = res.tool();
                // Normalize command text (records are immutable)
                String normalizedCmd = null;
                if (tool != null && tool.command() != null) {
                    normalizedCmd = tool.command().trim();
                    if (normalizedCmd.startsWith("/")) {
                        normalizedCmd = normalizedCmd.substring(1).trim();
                    }
                }
                FabricAiChatMod.LOGGER.debug("AI tool={} command={}{}",
                        tool != null ? tool.tool() : null,
                        normalizedCmd,
                        (normalizedCmd != null ? "" : " (null)"));

                FabricAiChatMod.LOGGER.debug("AI reply length={} preview=\"{}\"", reply != null ? reply.length() : -1,
                        reply != null ? reply.substring(0, Math.min(200, reply.length())).replaceAll("\n", "\\n")
                                : "null");

                // Update history (trim)
                h.addLast(new ChatMessage("user", userMsg));
                if (tool != null && "run_command".equalsIgnoreCase(tool.tool())) {
                    String cmdForHistory = normalizedCmd != null ? normalizedCmd : "<missing>";
                    h.addLast(new ChatMessage("assistant", "<tool:run_command /" + cmdForHistory + ">"));
                } else {
                    h.addLast(new ChatMessage("assistant", reply != null ? reply : ""));
                }
                while (h.size() > cfgFinal.maxHistory * 2) { // pairs of user+assistant
                    h.pollFirst();
                }

                if (tool != null && "run_command".equalsIgnoreCase(tool.tool()) && cfgFinal.allowRunCommands) {
                    FabricAiChatMod.LOGGER.debug("AI detected tool call: tool=run_command command={}",
                            normalizedCmd);
                    String cmd = normalizedCmd != null ? normalizedCmd : "";
                    if (cmd.isEmpty()) {
                        FabricAiChatMod.LOGGER.debug("AI tool call rejected: empty command");
                        src.sendError(Text.literal("AI requested a command, but it was empty."));
                        if (reply != null && !reply.isBlank()) {
                            src.sendFeedback(() -> Text.literal("\u00a7b[AI] " + reply), false);
                        }
                        return;
                    }

                    // Check allowlist
                    boolean allowed = (cfgFinal.commandAllowlist == null || cfgFinal.commandAllowlist.isEmpty())
                            || cfgFinal.commandAllowlist.stream().anyMatch(prefix -> cmd.startsWith(prefix));
                    if (!allowed) {
                        FabricAiChatMod.LOGGER.debug("AI tool call rejected by allowlist: {}", cmd);
                        src.sendError(Text.literal("Command '/" + cmd + "' not allowed."));
                        if (reply != null && !reply.isBlank()) {
                            src.sendFeedback(() -> Text.literal("\u00a7b[AI] " + reply), false);
                        }
                        return;
                    }

                    // Execute on server thread
                    MinecraftServer server = FabricAiChatMod.getServer();
                    if (server != null) {
                        server.execute(() -> {
                            try {
                                // Inform the user and the logs what will be run
                                FabricAiChatMod.LOGGER.debug("Executing AI command as {}: /{}",
                                        (src.getEntity() != null ? src.getEntity().getName().getString() : "server"),
                                        cmd);
                                src.sendFeedback(() -> Text.literal("\u00a77[AI] Executing: /" + cmd), false);

                                // Execute with player's context when available so selectors like @s work
                                String wrapped;
                                if (src.getEntity() != null) {
                                    String playerName = src.getEntity().getName().getString();
                                    wrapped = "execute as " + playerName + " at @s run " + cmd;
                                } else {
                                    // No entity source (e.g., console) — run as console
                                    wrapped = cmd;
                                }

                                // Run as server (perm level 4)
                                server.getCommandManager().executeWithPrefix(server.getCommandSource(), wrapped);
                            } catch (Exception e) {
                                src.sendError(Text.literal("Command failed: " + e.getMessage()));
                            }
                        });
                    }
                } else {
                    // No tool (or tools not allowed): send normal chat reply
                    src.sendFeedback(() -> Text.literal("\u00a7b[AI] " + (reply != null ? reply : "")), false);
                }
            } catch (Exception e) {
                FabricAiChatMod.LOGGER.warn("AI error while processing request: {}", e.toString());
                src.sendError(Text.literal("AI error: " + e.getMessage()));
            }
        });
    }

    private static UUID getSenderId(ServerCommandSource src) {
        try {
            return src.getPlayer() != null ? src.getPlayer().getUuid() : new UUID(0, 0);
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }
}
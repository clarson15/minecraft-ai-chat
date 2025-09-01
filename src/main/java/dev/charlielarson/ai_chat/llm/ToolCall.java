package dev.charlielarson.ai_chat.llm;

/**
 * Structured request from the model to call a tool.
 */
public record ToolCall(String tool, String command) {
}
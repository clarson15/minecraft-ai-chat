package dev.charlielarson.ai_chat.llm;

/**
 * Immutable chat message used for LLM interactions.
 */
public record ChatMessage(String role, String content) {
}
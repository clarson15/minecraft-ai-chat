package dev.charlielarson.ai_chat.llm;

import java.util.List;

public interface LlmProvider {
    record Result(String text, ToolCall tool) {
    }

    Result chat(List<ChatMessage> messages, double temperature, int maxTokens) throws Exception;
}
package dev.zanzibar.intelligence.dto;

public record ExplainResponse(
        boolean granted,
        String traceText,
        String systemPrompt,
        String userMessage
) {}

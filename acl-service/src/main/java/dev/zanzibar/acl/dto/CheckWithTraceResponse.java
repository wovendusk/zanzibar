package dev.zanzibar.acl.dto;

public record CheckWithTraceResponse(
        boolean granted,
        String traceText,
        String systemPrompt,
        String userMessage
) {}

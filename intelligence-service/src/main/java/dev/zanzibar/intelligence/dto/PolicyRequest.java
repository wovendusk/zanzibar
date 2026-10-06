package dev.zanzibar.intelligence.dto;

/**
 * @param description the policy in English
 * @param install     true to put the compiled config into force; optional, default false
 */
public record PolicyRequest(String description, Boolean install) {
}

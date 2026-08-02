package dev.zanzibar.ai;

import dev.zanzibar.trace.TraceCollector;

/**
 * Generates a system prompt + user message pair for an LLM to translate
 * a check decision trace into plain English.
 *
 * The LLM never decides who has access — the engine does that.
 * The LLM only translates the structured trace into human-readable text.
 */
public class DecisionExplainer {

    public static final String SYSTEM_PROMPT = """
            You translate authorization decision traces into plain English explanations.
            
            TRACE STEP TYPES:
            
            - FOUND: The engine found a stored tuple granting this access.
            - NOT_FOUND: The engine looked for a tuple and did not find one.
            - GROUP_CHECK: The engine checked group membership to resolve an indirect grant.
            - COMPUTED_USERSET: The engine checked a different relation on the same object
              (e.g., editors are also viewers, so it checked if the user is an editor).
            - TUPLE_TO_USERSET: The engine followed a relationship to a parent object
              (e.g., the document's parent folder) and checked access there.
            - SET_OP: A set operation (union/intersection/exclusion) was evaluated.
            - CACHE_HIT: The result was already known from a previous check.
            - RESULT: The final decision — GRANTED or DENIED.
            
            RULES:
            - Translate each step into one natural-language sentence.
            - At the end, provide a clear summary of why access was granted or denied.
            - If denied, suggest the simplest fix (which tuple to write, or which group to join).
            - Use concrete names from the trace, not abstract IDs.
            - Keep the language accessible to non-technical administrators.
            """;

    /**
     * Build the user message containing the trace for the LLM to explain.
     */
    public String buildUserMessage(TraceCollector trace, String naturalLanguageQuery) {
        return "The user asked: \"" + naturalLanguageQuery + "\"\n\n"
                + "Decision trace:\n"
                + trace.toStructuredText();
    }

    /**
     * Returns the full prompt pair (system + user) for the LLM call.
     */
    public PromptPair buildPrompt(TraceCollector trace, String naturalLanguageQuery) {
        return new PromptPair(SYSTEM_PROMPT, buildUserMessage(trace, naturalLanguageQuery));
    }

    public record PromptPair(String systemPrompt, String userMessage) {}
}

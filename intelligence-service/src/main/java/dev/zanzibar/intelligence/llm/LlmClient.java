package dev.zanzibar.intelligence.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one prompt to the OpenAI chat completions API and returns the reply text.
 */
@Component
public class LlmClient {

    private final RestClient http;
    private final String apiKey;
    private final String model;

    public LlmClient(@Value("${openai.base-url}") String baseUrl,
                     @Value("${openai.api-key}") String apiKey,
                     @Value("${openai.model}") String model) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * @param systemPrompt the fixed instructions
     * @param userMessage  the input for this call
     * @param jsonReply    true to make the model answer with a JSON object
     */
    public String complete(String systemPrompt, String userMessage, boolean jsonReply) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("No OpenAI API key configured; set OPENAI_API_KEY");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!model.startsWith("gpt-5")) {
            // 0 makes the reply as repeatable as possible. The gpt-5 models
            // only accept their default temperature, so it is left out for them.
            body.put("temperature", 0);
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        if (jsonReply) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        JsonNode reply = http.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (reply == null) {
            throw new IllegalStateException("Empty reply from the LLM");
        }
        return reply.path("choices").path(0).path("message").path("content").asText();
    }
}

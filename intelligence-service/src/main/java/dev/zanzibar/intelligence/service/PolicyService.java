package dev.zanzibar.intelligence.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.intelligence.client.AclClient;
import dev.zanzibar.intelligence.dto.PolicyResponse;
import dev.zanzibar.intelligence.llm.LlmClient;
import dev.zanzibar.intelligence.llm.Prompts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles an English policy description into namespace configs.
 *
 * The model writes a JSON document built from six fixed rule kinds. The ACL
 * service then validates that document; only a document that passes can be
 * installed. So a structural mistake by the model produces a rejected config,
 * not a wrong access decision. Validation cannot tell whether a well-formed
 * config means what the author intended, so a person should still review it.
 */
@Service
public class PolicyService {

    private final LlmClient llm;
    private final AclClient acl;
    private final ObjectMapper json;

    public PolicyService(LlmClient llm, AclClient acl, ObjectMapper json) {
        this.llm = llm;
        this.acl = acl;
        this.json = json;
    }

    private static final int MAX_ATTEMPTS = 3;

    /**
     * @param install true to put the compiled config into force if it is valid
     */
    public PolicyResponse compile(String description, boolean install) {
        String prompt = description;
        String document = null;
        String error = null;

        // If the ACL service rejects the model's document, show the model the
        // reason and let it try again, a limited number of times.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            document = llm.complete(Prompts.COMPILE_POLICY, prompt, true);

            AclClient.NamespaceValidation validation = acl.validateNamespaces(document);
            if (validation.valid()) {
                if (install) {
                    acl.installNamespaces(document);
                }
                return new PolicyResponse(document, suggestedTuples(document), true, null, install, attempt);
            }

            error = validation.error();
            prompt = description
                    + "\n\nYour previous answer was rejected by the validator."
                    + "\nReason: " + error
                    + "\nPrevious answer:\n" + document
                    + "\nReply with a corrected JSON object.";
        }
        return new PolicyResponse(document, List.of(), false, error, false, MAX_ATTEMPTS);
    }

    /** The "tuples" list from the model's document. These are suggestions and are not written. */
    private List<String> suggestedTuples(String document) {
        List<String> tuples = new ArrayList<>();
        try {
            JsonNode list = json.readTree(document).path("tuples");
            for (JsonNode tuple : list) {
                tuples.add(tuple.asText());
            }
        } catch (JsonProcessingException e) {
            // The document already passed validation, so this cannot happen;
            // returning no suggestions is harmless if it somehow does.
        }
        return tuples;
    }
}

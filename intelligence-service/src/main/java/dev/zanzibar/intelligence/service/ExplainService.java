package dev.zanzibar.intelligence.service;

import dev.zanzibar.intelligence.client.AclClient;
import dev.zanzibar.intelligence.dto.ExplainRequest;
import dev.zanzibar.intelligence.dto.ExplainResponse;
import dev.zanzibar.intelligence.llm.LlmClient;
import dev.zanzibar.intelligence.llm.Prompts;
import org.springframework.stereotype.Service;

/**
 * Explains a check decision in English.
 *
 * The ACL service makes the decision and returns the steps it took. The model
 * is only asked to reword those steps; the granted/denied answer in the
 * response is the engine's, never the model's.
 */
@Service
public class ExplainService {

    private final AclClient acl;
    private final LlmClient llm;

    public ExplainService(AclClient acl, LlmClient llm) {
        this.acl = acl;
        this.llm = llm;
    }

    public ExplainResponse explain(ExplainRequest request) {
        AclClient.TracedCheck check = acl.checkWithTrace(request);

        String subject = request.subjectNs() + ":" + request.subjectId();
        if (request.subjectRel() != null) {
            subject = subject + "#" + request.subjectRel();
        }
        String question = "Question: does " + subject + " have \"" + request.relation()
                + "\" on " + request.resourceNs() + ":" + request.resourceId() + "?";
        String userMessage = question + "\n\nTrace:\n" + check.trace();

        String explanation = llm.complete(Prompts.EXPLAIN, userMessage, false);
        return new ExplainResponse(check.granted(), check.evaluatedAtRevision(), check.trace(), explanation);
    }
}

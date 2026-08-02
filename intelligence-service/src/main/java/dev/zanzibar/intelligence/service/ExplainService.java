package dev.zanzibar.intelligence.service;

import dev.zanzibar.intelligence.dto.ExplainResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls the ACL Service's check/explain endpoint and returns
 * the decision trace with LLM prompt.
 */
@Service
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    private final RestClient restClient;

    public ExplainService(@Value("${acl.service.url:http://localhost:8081}") String aclServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(aclServiceUrl)
                .build();
    }

    public ExplainResponse explain(String resourceNs, String resourceId,
                                   String relation, String subjectNs, String subjectId,
                                   String subjectRel) {
        record CheckRequest(String resourceNs, String resourceId, String relation,
                            String subjectNs, String subjectId, String subjectRel,
                            Long zookieRevision) {}
        record AclResponse(boolean granted, String traceText,
                           String systemPrompt, String userMessage) {}

        var aclResponse = restClient.post()
                .uri("/api/v1/check/explain")
                .body(new CheckRequest(resourceNs, resourceId, relation,
                        subjectNs, subjectId, subjectRel, null))
                .retrieve()
                .body(AclResponse.class);

        if (aclResponse == null) {
            return new ExplainResponse(false, "Unable to get explanation from ACL service",
                    "", "");
        }

        return new ExplainResponse(
                aclResponse.granted(),
                aclResponse.traceText(),
                aclResponse.systemPrompt(),
                aclResponse.userMessage());
    }
}

package dev.zanzibar.intelligence.client;

import dev.zanzibar.intelligence.dto.ExplainRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Calls the ACL service over HTTP. */
@Component
public class AclClient {

    /** What the ACL service returns for a traced check. */
    public record TracedCheck(boolean granted, long evaluatedAtRevision, String trace) {
    }

    /** What the ACL service returns when asked to validate or install namespace configs. */
    public record NamespaceValidation(boolean valid, String error, List<String> namespaces) {
    }

    private final RestClient http;

    public AclClient(@Value("${acl.service.url}") String aclServiceUrl) {
        this.http = RestClient.builder().baseUrl(aclServiceUrl).build();
    }

    public TracedCheck checkWithTrace(ExplainRequest request) {
        return http.post()
                .uri("/api/v1/check/explain")
                .body(request)
                .retrieve()
                .body(TracedCheck.class);
    }

    public NamespaceValidation validateNamespaces(String document) {
        return http.post()
                .uri("/api/v1/namespaces/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(document)
                .retrieve()
                .body(NamespaceValidation.class);
    }

    public NamespaceValidation installNamespaces(String document) {
        return http.put()
                .uri("/api/v1/namespaces")
                .contentType(MediaType.APPLICATION_JSON)
                .body(document)
                .retrieve()
                .body(NamespaceValidation.class);
    }
}

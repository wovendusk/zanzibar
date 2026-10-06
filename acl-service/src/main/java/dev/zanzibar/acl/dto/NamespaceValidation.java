package dev.zanzibar.acl.dto;

import java.util.List;

/**
 * Result of validating a namespace config document.
 *
 * @param valid      whether every namespace in the document is acceptable
 * @param error      what is wrong, or null when valid
 * @param namespaces the names of the namespaces in the document when valid
 */
public record NamespaceValidation(boolean valid, String error, List<String> namespaces) {
}

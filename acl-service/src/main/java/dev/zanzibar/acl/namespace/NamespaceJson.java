package dev.zanzibar.acl.namespace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Turns namespace configs written as JSON into NamespaceConfig objects,
 * rejecting anything that is not one of the six known rule kinds.
 *
 * <pre>
 * { "namespaces": [
 *     { "name": "doc",
 *       "relations": {
 *         "owner":  { "type": "this" },
 *         "viewer": { "type": "union", "children": [
 *             { "type": "this" },
 *             { "type": "computed_userset", "relation": "owner" } ] } } } ] }
 * </pre>
 *
 * This is the gate for configs produced by the LLM policy compiler: the model
 * can only choose and combine these building blocks, and a config that does
 * not parse here is never used.
 */
@Component
public class NamespaceJson {

    private final ObjectMapper json;

    public NamespaceJson(ObjectMapper json) {
        this.json = json;
    }

    /** One namespace as parsed config plus the JSON text it came from. */
    public record ParsedNamespace(NamespaceConfig config, String jsonText) {
    }

    /** Parses a whole document. Throws IllegalArgumentException describing the first problem. */
    public List<ParsedNamespace> parseDocument(String document) {
        JsonNode root;
        try {
            root = json.readTree(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage());
        }
        JsonNode namespaces = root.get("namespaces");
        if (namespaces == null || !namespaces.isArray() || namespaces.isEmpty()) {
            throw new IllegalArgumentException("Expected a non-empty \"namespaces\" array");
        }
        List<ParsedNamespace> parsed = new ArrayList<>();
        for (JsonNode namespace : namespaces) {
            parsed.add(new ParsedNamespace(parseNamespace(namespace), namespace.toString()));
        }
        return parsed;
    }

    /** Parses the JSON of one namespace, as stored in the database. */
    public NamespaceConfig parseNamespace(String namespaceJson) {
        try {
            return parseNamespace(json.readTree(namespaceJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage());
        }
    }

    private NamespaceConfig parseNamespace(JsonNode node) {
        String name = requiredText(node, "name", "namespace");
        JsonNode relations = node.get("relations");
        if (relations == null || !relations.isObject() || relations.isEmpty()) {
            throw new IllegalArgumentException("Namespace \"" + name + "\" needs a non-empty \"relations\" object");
        }

        NamespaceConfig.Builder builder = NamespaceConfig.builder(name);
        Iterator<Map.Entry<String, JsonNode>> fields = relations.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            builder.relation(field.getKey(), parseRule(field.getValue(), relations, name));
        }
        return builder.build();
    }

    private RewriteRule parseRule(JsonNode node, JsonNode relations, String namespace) {
        String type = requiredText(node, "type", "rule in namespace \"" + namespace + "\"");
        switch (type) {
            case "this":
                return RewriteRule.thisRelation();

            case "computed_userset": {
                String relation = requiredText(node, "relation", "computed_userset");
                requireRelation(relations, relation, namespace);
                return RewriteRule.computedUserset(relation);
            }

            case "tuple_to_userset": {
                String tupleset = requiredText(node, "tupleset", "tuple_to_userset");
                String computed = requiredText(node, "computed", "tuple_to_userset");
                requireRelation(relations, tupleset, namespace);
                return RewriteRule.tupleToUserset(tupleset, computed);
            }

            case "union":
                return new RewriteRule.Union(parseChildren(node, relations, namespace));

            case "intersection":
                return new RewriteRule.Intersection(parseChildren(node, relations, namespace));

            case "exclusion": {
                JsonNode base = node.get("base");
                JsonNode subtract = node.get("subtract");
                if (base == null || subtract == null) {
                    throw new IllegalArgumentException("exclusion needs \"base\" and \"subtract\"");
                }
                RewriteRule baseRule = parseRule(base, relations, namespace);
                RewriteRule subtractRule = parseRule(subtract, relations, namespace);
                if (baseRule.equals(subtractRule)) {
                    throw new IllegalArgumentException(
                            "exclusion subtracts the same rule it starts from, so it would grant nobody");
                }
                return RewriteRule.exclusion(baseRule, subtractRule);
            }

            default:
                throw new IllegalArgumentException("Unknown rule type \"" + type + "\"");
        }
    }

    private List<RewriteRule> parseChildren(JsonNode node, JsonNode relations, String namespace) {
        JsonNode children = node.get("children");
        if (children == null || !children.isArray() || children.isEmpty()) {
            throw new IllegalArgumentException(node.get("type").asText() + " needs a non-empty \"children\" array");
        }
        List<RewriteRule> rules = new ArrayList<>();
        for (JsonNode child : children) {
            rules.add(parseRule(child, relations, namespace));
        }
        return rules;
    }

    private void requireRelation(JsonNode relations, String relation, String namespace) {
        if (!relations.has(relation)) {
            throw new IllegalArgumentException("Namespace \"" + namespace
                    + "\" refers to relation \"" + relation + "\" but does not define it");
        }
    }

    private String requiredText(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing \"" + field + "\" in " + where);
        }
        return value.asText();
    }
}

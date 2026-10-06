package dev.zanzibar.acl.namespace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
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
 *         "owner":  { "type": "direct" },
 *         "viewer": { "type": "union", "children": [
 *             { "type": "direct" },
 *             { "type": "computed_userset", "relation": "owner" } ] } } } ] }
 * </pre>
 *
 * "direct" is the rule the Zanzibar paper calls "this": the subjects stored in
 * tuples for the relation itself. Both names are accepted. The JSON uses
 * "direct" because a language model reads "this" as "the thing just mentioned".
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
        // The relations of every namespace in the document, by namespace name,
        // so that a rule pointing at another namespace can be checked against it.
        Map<String, JsonNode> relationsByNamespace = new HashMap<>();
        for (JsonNode namespace : namespaces) {
            JsonNode name = namespace.get("name");
            JsonNode relations = namespace.get("relations");
            if (name != null && relations != null) {
                relationsByNamespace.put(name.asText(), relations);
            }
        }

        List<ParsedNamespace> parsed = new ArrayList<>();
        for (JsonNode namespace : namespaces) {
            parsed.add(new ParsedNamespace(parseNamespace(namespace, relationsByNamespace), namespace.toString()));
        }
        return parsed;
    }

    /** Parses the JSON of one namespace, as stored in the database. */
    public NamespaceConfig parseNamespace(String namespaceJson) {
        try {
            return parseNamespace(json.readTree(namespaceJson), Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage());
        }
    }

    private NamespaceConfig parseNamespace(JsonNode node, Map<String, JsonNode> relationsByNamespace) {
        String name = requiredText(node, "name", "namespace");
        JsonNode relations = node.get("relations");
        if (relations == null || !relations.isObject() || relations.isEmpty()) {
            throw new IllegalArgumentException("Namespace \"" + name + "\" needs a non-empty \"relations\" object");
        }

        NamespaceConfig.Builder builder = NamespaceConfig.builder(name);
        Iterator<Map.Entry<String, JsonNode>> fields = relations.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            // A relation is either a rule, or {"means": "...", "rule": <rule>}.
            // "means" is a sentence for human readers and is not used here.
            JsonNode rule = field.getValue();
            if (rule.has("rule")) {
                rule = rule.get("rule");
            }
            builder.relation(field.getKey(), parseRule(rule, relations, name, relationsByNamespace));
        }
        return builder.build();
    }

    private RewriteRule parseRule(JsonNode node, JsonNode relations, String namespace,
                                  Map<String, JsonNode> relationsByNamespace) {
        String type = requiredText(node, "type", "rule in namespace \"" + namespace + "\"");
        switch (type) {
            case "direct":
            case "this": // the paper's name for the same thing
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
                requireRelationOnTarget(node, computed, namespace, relationsByNamespace);
                return RewriteRule.tupleToUserset(tupleset, computed);
            }

            case "union":
                return new RewriteRule.Union(parseChildren(node, relations, namespace, relationsByNamespace));

            case "intersection":
                return new RewriteRule.Intersection(parseChildren(node, relations, namespace, relationsByNamespace));

            case "exclusion": {
                JsonNode base = node.get("base");
                JsonNode subtract = node.get("subtract");
                if (base == null || subtract == null) {
                    throw new IllegalArgumentException("exclusion needs \"base\" and \"subtract\"");
                }
                RewriteRule baseRule = parseRule(base, relations, namespace, relationsByNamespace);
                RewriteRule subtractRule = parseRule(subtract, relations, namespace, relationsByNamespace);
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

    private List<RewriteRule> parseChildren(JsonNode node, JsonNode relations, String namespace,
                                            Map<String, JsonNode> relationsByNamespace) {
        JsonNode children = node.get("children");
        if (children == null || !children.isArray() || children.isEmpty()) {
            throw new IllegalArgumentException(node.get("type").asText() + " needs a non-empty \"children\" array");
        }
        List<RewriteRule> rules = new ArrayList<>();
        for (JsonNode child : children) {
            rules.add(parseRule(child, relations, namespace, relationsByNamespace));
        }
        return rules;
    }

    private void requireRelation(JsonNode relations, String relation, String namespace) {
        if (!relations.has(relation)) {
            throw new IllegalArgumentException("Namespace \"" + namespace
                    + "\" refers to relation \"" + relation + "\" but does not define it");
        }
    }

    /**
     * A tuple_to_userset may say which namespace its link leads to ("target").
     * If that namespace is in the same document, the relation taken there
     * must exist in it.
     */
    private void requireRelationOnTarget(JsonNode node, String computed, String namespace,
                                         Map<String, JsonNode> relationsByNamespace) {
        JsonNode target = node.get("target");
        if (target == null) {
            return;
        }
        JsonNode targetRelations = relationsByNamespace.get(target.asText());
        if (targetRelations == null || targetRelations.has(computed)) {
            return;
        }
        List<String> available = new ArrayList<>();
        Iterator<String> names = targetRelations.fieldNames();
        while (names.hasNext()) {
            available.add(names.next());
        }
        throw new IllegalArgumentException("A tuple_to_userset in \"" + namespace + "\" takes relation \""
                + computed + "\" from \"" + target.asText() + "\", but \"" + target.asText()
                + "\" has no such relation. Its relations are: " + available);
    }

    private String requiredText(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing \"" + field + "\" in " + where);
        }
        return value.asText();
    }
}

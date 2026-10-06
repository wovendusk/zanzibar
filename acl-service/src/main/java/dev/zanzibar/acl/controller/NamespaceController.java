package dev.zanzibar.acl.controller;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.dto.NamespaceValidation;
import dev.zanzibar.acl.namespace.NamespaceJson;
import dev.zanzibar.acl.namespace.NamespaceStore;
import dev.zanzibar.config.NamespaceConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Validate and install namespace configs written as JSON.
 * The request body is the JSON document itself (see NamespaceJson for the format).
 */
@RestController
@RequestMapping("/api/v1/namespaces")
public class NamespaceController {

    private final ZanzibarEngine engine;
    private final NamespaceJson namespaceJson;
    private final NamespaceStore namespaceStore;

    public NamespaceController(ZanzibarEngine engine, NamespaceJson namespaceJson,
                               NamespaceStore namespaceStore) {
        this.engine = engine;
        this.namespaceJson = namespaceJson;
        this.namespaceStore = namespaceStore;
    }

    /** Says whether a document is acceptable, without changing anything. */
    @PostMapping("/validate")
    public NamespaceValidation validate(@RequestBody String document) {
        try {
            return new NamespaceValidation(true, null, names(namespaceJson.parseDocument(document)));
        } catch (IllegalArgumentException e) {
            return new NamespaceValidation(false, e.getMessage(), List.of());
        }
    }

    /** Saves the namespaces in a document and puts them into force. Invalid documents get a 400. */
    @PutMapping
    public NamespaceValidation install(@RequestBody String document) {
        List<NamespaceJson.ParsedNamespace> parsed = namespaceJson.parseDocument(document);
        for (NamespaceJson.ParsedNamespace namespace : parsed) {
            namespaceStore.save(namespace.config().name(), namespace.jsonText());
            engine.registerNamespace(namespace.config());
        }
        return new NamespaceValidation(true, null, names(parsed));
    }

    /** The names of the namespaces currently in force. */
    @GetMapping
    public List<String> list() {
        List<String> names = new ArrayList<>();
        for (NamespaceConfig config : engine.namespaces()) {
            names.add(config.name());
        }
        return names;
    }

    private List<String> names(List<NamespaceJson.ParsedNamespace> parsed) {
        List<String> names = new ArrayList<>();
        for (NamespaceJson.ParsedNamespace namespace : parsed) {
            names.add(namespace.config().name());
        }
        return names;
    }
}

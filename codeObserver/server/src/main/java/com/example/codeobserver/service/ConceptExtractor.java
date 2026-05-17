package com.example.codeobserver.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ConceptExtractor {
    private static final Pattern CAMEL_TOKEN = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\\d+");
    private static final Set<String> STOP_WORDS = Set.of(
            "abstract", "args", "array", "boolean", "bplus", "btree", "builder", "byte", "char",
            "class", "code", "codeobserver", "collection", "com", "create", "demo", "double", "enum",
            "example", "extends", "false", "final", "float", "get", "int", "integer", "interface",
            "java", "key", "list", "long", "main", "map", "mapping", "new", "object", "observer",
            "optional", "override", "package", "plus", "private", "protected", "public", "put",
            "record", "response", "return", "run", "set", "short", "side", "static", "string",
            "this", "true", "until", "var", "void"
    );

    private static final List<ConceptRule> RULES = List.of(
            new ConceptRule("kafka", "Kafka"),
            new ConceptRule("replica", "Replica"),
            new ConceptRule("replication", "Replication"),
            new ConceptRule("leader", "Leader Election"),
            new ConceptRule("follower", "Follower"),
            new ConceptRule("isr", "ISR"),
            new ConceptRule("watermark", "High Watermark"),
            new ConceptRule("transaction", "Transaction"),
            new ConceptRule("coordinator", "Coordinator"),
            new ConceptRule("controller", "Controller"),
            new ConceptRule("epoch", "Epoch"),
            new ConceptRule("mysql", "MySQL"),
            new ConceptRule("innodb", "InnoDB"),
            new ConceptRule("mvcc", "MVCC"),
            new ConceptRule("readview", "ReadView"),
            new ConceptRule("undo", "Undo Log"),
            new ConceptRule("redo", "Redo Log"),
            new ConceptRule("wal", "WAL"),
            new ConceptRule("btree", "B+Tree"),
            new ConceptRule("bplus", "B+Tree"),
            new ConceptRule("index", "Index"),
            new ConceptRule("lock", "Lock"),
            new ConceptRule("isolation", "Isolation Level"),
            new ConceptRule("cache", "Cache"),
            new ConceptRule("parser", "Parser"),
            new ConceptRule("scheduler", "Scheduler"),
            new ConceptRule("network", "Network"),
            new ConceptRule("storage", "Storage"),
            new ConceptRule("log", "Log"),
            new ConceptRule("recovery", "Recovery"),
            new ConceptRule("state", "State Machine")
    );

    public List<String> fromText(String text, int limit) {
        Set<String> concepts = new LinkedHashSet<>();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (ConceptRule rule : RULES) {
            if (normalized.contains(rule.token())) {
                concepts.add(rule.label());
            }
        }

        Matcher matcher = CAMEL_TOKEN.matcher(text == null ? "" : text);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group();
            String normalizedToken = value.toLowerCase(Locale.ROOT);
            if (normalizedToken.length() > 2 && !STOP_WORDS.contains(normalizedToken)) {
                tokens.add(capitalize(normalizedToken));
            }
        }
        tokens.stream()
                .filter(token -> concepts.stream().noneMatch(existing -> existing.equalsIgnoreCase(token)))
                .limit(Math.max(0, limit - concepts.size()))
                .forEach(concepts::add);

        return concepts.stream().limit(limit).toList();
    }

    @SafeVarargs
    public final List<String> merge(int limit, List<String>... lists) {
        Set<String> concepts = new LinkedHashSet<>();
        for (List<String> list : lists) {
            if (list != null) {
                concepts.addAll(list);
            }
        }
        return concepts.stream().limit(limit).toList();
    }

    private String capitalize(String value) {
        if (value.isBlank()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record ConceptRule(String token, String label) {
    }
}

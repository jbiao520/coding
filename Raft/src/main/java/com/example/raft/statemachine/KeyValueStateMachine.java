package com.example.raft.statemachine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public class KeyValueStateMachine implements StateMachine {
    private final Map<String, String> data = new LinkedHashMap<>();

    @Override
    public synchronized void apply(String command) {
        if (command == null || command.isBlank() || "noop".equals(command)) {
            return;
        }

        String[] parts = command.trim().split("\\s+", 3);
        switch (parts[0].toLowerCase()) {
            case "set" -> {
                if (parts.length != 3) {
                    throw new IllegalArgumentException("set command must be: set <key> <value>");
                }
                data.put(parts[1], parts[2]);
            }
            case "delete" -> {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("delete command must be: delete <key>");
                }
                data.remove(parts[1]);
            }
            default -> throw new IllegalArgumentException("unsupported command: " + command);
        }
    }

    @Override
    public synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}

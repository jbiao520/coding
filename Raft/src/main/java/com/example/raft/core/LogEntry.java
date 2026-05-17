package com.example.raft.core;

public record LogEntry(int term, String command) {
    public boolean isNoop() {
        return "noop".equals(command);
    }
}

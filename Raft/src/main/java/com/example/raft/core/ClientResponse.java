package com.example.raft.core;

public record ClientResponse(boolean success, String leaderId, int term, int logIndex, String message) {
    public static ClientResponse redirected(String leaderId, int term) {
        return new ClientResponse(false, leaderId, term, -1, "not leader");
    }

    public static ClientResponse committed(String leaderId, int term, int logIndex) {
        return new ClientResponse(true, leaderId, term, logIndex, "committed");
    }

    public static ClientResponse failed(String leaderId, int term, int logIndex, String message) {
        return new ClientResponse(false, leaderId, term, logIndex, message);
    }
}

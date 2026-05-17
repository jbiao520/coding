package com.example.raft.rpc;

public record AppendEntriesResponse(int term, boolean success, int matchIndex) {
}

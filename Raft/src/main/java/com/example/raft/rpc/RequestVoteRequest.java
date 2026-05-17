package com.example.raft.rpc;

public record RequestVoteRequest(
        int term,
        String candidateId,
        int lastLogIndex,
        int lastLogTerm
) {
}

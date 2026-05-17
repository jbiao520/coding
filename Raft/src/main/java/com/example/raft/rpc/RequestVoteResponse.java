package com.example.raft.rpc;

public record RequestVoteResponse(int term, boolean voteGranted) {
}

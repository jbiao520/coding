package com.example.raft.rpc;

import com.example.raft.core.LogEntry;

import java.util.List;

public record AppendEntriesRequest(
        int term,
        String leaderId,
        int prevLogIndex,
        int prevLogTerm,
        List<LogEntry> entries,
        int leaderCommit
) {
}

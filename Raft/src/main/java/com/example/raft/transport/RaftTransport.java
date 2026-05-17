package com.example.raft.transport;

import com.example.raft.rpc.AppendEntriesRequest;
import com.example.raft.rpc.AppendEntriesResponse;
import com.example.raft.rpc.RequestVoteRequest;
import com.example.raft.rpc.RequestVoteResponse;

public interface RaftTransport {
    RequestVoteResponse requestVote(String targetId, RequestVoteRequest request);

    AppendEntriesResponse appendEntries(String targetId, AppendEntriesRequest request);
}

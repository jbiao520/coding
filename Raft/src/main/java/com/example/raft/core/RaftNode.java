package com.example.raft.core;

import com.example.raft.rpc.AppendEntriesRequest;
import com.example.raft.rpc.AppendEntriesResponse;
import com.example.raft.rpc.RequestVoteRequest;
import com.example.raft.rpc.RequestVoteResponse;
import com.example.raft.statemachine.StateMachine;
import com.example.raft.transport.RaftTransport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.StringJoiner;

public class RaftNode {
    private static final int HEARTBEAT_INTERVAL_TICKS = 2;

    private final String id;
    private final List<String> peers;
    private final RaftTransport transport;
    private final StateMachine stateMachine;
    private final Random random;
    private final List<LogEntry> log = new ArrayList<>();
    private final Map<String, Integer> nextIndex = new HashMap<>();
    private final Map<String, Integer> matchIndex = new HashMap<>();

    private RaftRole role = RaftRole.FOLLOWER;
    private int currentTerm;
    private String votedFor;
    private String leaderId;
    private int commitIndex;
    private int lastApplied;
    private int electionElapsed;
    private int electionTimeoutTicks;
    private int heartbeatElapsed;

    public RaftNode(String id, List<String> peers, RaftTransport transport, StateMachine stateMachine) {
        this.id = Objects.requireNonNull(id);
        this.peers = List.copyOf(peers);
        this.transport = Objects.requireNonNull(transport);
        this.stateMachine = Objects.requireNonNull(stateMachine);
        this.random = new Random(id.hashCode());
        this.log.add(new LogEntry(0, "bootstrap"));
        resetElectionTimer();
    }

    public synchronized void tick() {
        if (role == RaftRole.LEADER) {
            heartbeatElapsed++;
            if (heartbeatElapsed >= HEARTBEAT_INTERVAL_TICKS) {
                heartbeatElapsed = 0;
                replicateToFollowers();
            }
            return;
        }

        electionElapsed++;
        if (electionElapsed >= electionTimeoutTicks) {
            startElection();
        }
    }

    public synchronized ClientResponse propose(String command) {
        if (role != RaftRole.LEADER) {
            return ClientResponse.redirected(leaderId, currentTerm);
        }

        log.add(new LogEntry(currentTerm, command));
        int index = lastLogIndex();
        replicateToFollowers();
        if (commitIndex >= index) {
            replicateToFollowers();
            return ClientResponse.committed(id, currentTerm, index);
        }
        return ClientResponse.failed(id, currentTerm, index, "entry was not replicated to a majority");
    }

    public synchronized RequestVoteResponse onRequestVote(RequestVoteRequest request) {
        if (request.term() < currentTerm) {
            return new RequestVoteResponse(currentTerm, false);
        }

        if (request.term() > currentTerm) {
            stepDown(request.term());
        }

        boolean candidateLogIsFresh = isCandidateLogAtLeastAsFresh(
                request.lastLogIndex(),
                request.lastLogTerm()
        );
        boolean canVoteForCandidate = votedFor == null || votedFor.equals(request.candidateId());
        boolean voteGranted = canVoteForCandidate && candidateLogIsFresh;

        if (voteGranted) {
            votedFor = request.candidateId();
            resetElectionTimer();
        }

        return new RequestVoteResponse(currentTerm, voteGranted);
    }

    public synchronized AppendEntriesResponse onAppendEntries(AppendEntriesRequest request) {
        if (request.term() < currentTerm) {
            return new AppendEntriesResponse(currentTerm, false, lastLogIndex());
        }

        if (request.term() > currentTerm || role != RaftRole.FOLLOWER) {
            stepDown(request.term());
        }

        leaderId = request.leaderId();
        resetElectionTimer();

        if (request.prevLogIndex() > lastLogIndex()) {
            return new AppendEntriesResponse(currentTerm, false, lastLogIndex());
        }
        if (log.get(request.prevLogIndex()).term() != request.prevLogTerm()) {
            return new AppendEntriesResponse(currentTerm, false, request.prevLogIndex() - 1);
        }

        int insertIndex = request.prevLogIndex() + 1;
        for (LogEntry entry : request.entries()) {
            if (insertIndex <= lastLogIndex()) {
                if (log.get(insertIndex).term() != entry.term()) {
                    removeLogEntriesFrom(insertIndex);
                    log.add(entry);
                }
            } else {
                log.add(entry);
            }
            insertIndex++;
        }

        if (request.leaderCommit() > commitIndex) {
            commitIndex = Math.min(request.leaderCommit(), lastLogIndex());
            applyCommittedEntries();
        }

        return new AppendEntriesResponse(currentTerm, true, lastLogIndex());
    }

    public synchronized void restartElectionTimer() {
        resetElectionTimer();
    }

    public synchronized String id() {
        return id;
    }

    public synchronized RaftRole role() {
        return role;
    }

    public synchronized int currentTerm() {
        return currentTerm;
    }

    public synchronized String leaderId() {
        return leaderId;
    }

    public synchronized int commitIndex() {
        return commitIndex;
    }

    public synchronized int lastLogIndex() {
        return log.size() - 1;
    }

    public synchronized Map<String, String> stateSnapshot() {
        return stateMachine.snapshot();
    }

    public synchronized String logSummary() {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (int i = 1; i < log.size(); i++) {
            LogEntry entry = log.get(i);
            joiner.add(i + ":" + entry.command() + "@t" + entry.term());
        }
        return joiner.toString();
    }

    private void startElection() {
        role = RaftRole.CANDIDATE;
        currentTerm++;
        votedFor = id;
        leaderId = null;
        heartbeatElapsed = 0;
        resetElectionTimer();

        int votes = 1;
        RequestVoteRequest request = new RequestVoteRequest(
                currentTerm,
                id,
                lastLogIndex(),
                lastLogTerm()
        );

        for (String peer : peers) {
            RequestVoteResponse response = transport.requestVote(peer, request);
            if (response.term() > currentTerm) {
                stepDown(response.term());
                return;
            }
            if (role == RaftRole.CANDIDATE && response.voteGranted()) {
                votes++;
                if (hasMajority(votes)) {
                    becomeLeader();
                    return;
                }
            }
        }

        if (peers.isEmpty()) {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        role = RaftRole.LEADER;
        leaderId = id;
        heartbeatElapsed = 0;
        for (String peer : peers) {
            nextIndex.put(peer, lastLogIndex() + 1);
            matchIndex.put(peer, 0);
        }

        log.add(new LogEntry(currentTerm, "noop"));
        replicateToFollowers();
    }

    private void replicateToFollowers() {
        if (role != RaftRole.LEADER) {
            return;
        }

        boolean commitAdvanced = false;
        for (String peer : peers) {
            replicateToPeer(peer);
            commitAdvanced = advanceCommitIndex() || commitAdvanced;
        }

        if (commitAdvanced) {
            for (String peer : peers) {
                sendAppendEntries(peer, List.of());
            }
        }
    }

    private void replicateToPeer(String peer) {
        while (role == RaftRole.LEADER) {
            int next = nextIndex.getOrDefault(peer, lastLogIndex() + 1);
            List<LogEntry> entries = List.copyOf(log.subList(next, log.size()));
            AppendEntriesResponse response = sendAppendEntries(peer, entries);

            if (response.term() > currentTerm) {
                stepDown(response.term());
                return;
            }

            if (response.success()) {
                int replicatedThrough = next + entries.size() - 1;
                int peerMatchIndex = Math.max(response.matchIndex(), replicatedThrough);
                matchIndex.put(peer, peerMatchIndex);
                nextIndex.put(peer, peerMatchIndex + 1);
                return;
            }

            if (next <= 1) {
                return;
            }
            nextIndex.put(peer, next - 1);
        }
    }

    private AppendEntriesResponse sendAppendEntries(String peer, List<LogEntry> entries) {
        int next = nextIndex.getOrDefault(peer, lastLogIndex() + 1);
        int prevIndex = next - 1;
        AppendEntriesRequest request = new AppendEntriesRequest(
                currentTerm,
                id,
                prevIndex,
                log.get(prevIndex).term(),
                entries,
                commitIndex
        );
        return transport.appendEntries(peer, request);
    }

    private boolean advanceCommitIndex() {
        for (int candidateIndex = lastLogIndex(); candidateIndex > commitIndex; candidateIndex--) {
            if (log.get(candidateIndex).term() != currentTerm) {
                continue;
            }

            int replicatedCount = 1;
            for (String peer : peers) {
                if (matchIndex.getOrDefault(peer, 0) >= candidateIndex) {
                    replicatedCount++;
                }
            }

            if (hasMajority(replicatedCount)) {
                commitIndex = candidateIndex;
                applyCommittedEntries();
                return true;
            }
        }
        return false;
    }

    private void applyCommittedEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.get(lastApplied);
            if (!entry.isNoop()) {
                stateMachine.apply(entry.command());
            }
        }
    }

    private void stepDown(int newTerm) {
        role = RaftRole.FOLLOWER;
        currentTerm = newTerm;
        votedFor = null;
        leaderId = null;
        heartbeatElapsed = 0;
        resetElectionTimer();
    }

    private boolean isCandidateLogAtLeastAsFresh(int candidateLastLogIndex, int candidateLastLogTerm) {
        int localLastLogTerm = lastLogTerm();
        return candidateLastLogTerm > localLastLogTerm
                || candidateLastLogTerm == localLastLogTerm && candidateLastLogIndex >= lastLogIndex();
    }

    private boolean hasMajority(int votesOrReplicas) {
        return votesOrReplicas > (peers.size() + 1) / 2;
    }

    private int lastLogTerm() {
        return log.get(lastLogIndex()).term();
    }

    private void removeLogEntriesFrom(int startIndex) {
        while (log.size() > startIndex) {
            log.remove(log.size() - 1);
        }
        if (commitIndex >= startIndex) {
            commitIndex = startIndex - 1;
        }
        if (lastApplied > commitIndex) {
            lastApplied = commitIndex;
        }
    }

    private void resetElectionTimer() {
        electionElapsed = 0;
        electionTimeoutTicks = 5 + random.nextInt(5);
    }
}

package com.example.raft.transport;

import com.example.raft.core.ClientResponse;
import com.example.raft.core.RaftNode;
import com.example.raft.core.RaftRole;
import com.example.raft.rpc.AppendEntriesRequest;
import com.example.raft.rpc.AppendEntriesResponse;
import com.example.raft.rpc.RequestVoteRequest;
import com.example.raft.rpc.RequestVoteResponse;
import com.example.raft.statemachine.KeyValueStateMachine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public class InMemoryRaftCluster implements RaftTransport {
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Set<String> stoppedNodes = new HashSet<>();
    private final Set<String> blockedLinks = new HashSet<>();

    public static InMemoryRaftCluster of(String... nodeIds) {
        InMemoryRaftCluster cluster = new InMemoryRaftCluster();
        List<String> ids = List.of(nodeIds);
        for (String id : ids) {
            List<String> peers = ids.stream()
                    .filter(peer -> !peer.equals(id))
                    .toList();
            cluster.nodes.put(id, new RaftNode(id, peers, cluster, new KeyValueStateMachine()));
        }
        return cluster;
    }

    @Override
    public synchronized RequestVoteResponse requestVote(String targetId, RequestVoteRequest request) {
        if (!canDeliver(request.candidateId(), targetId)) {
            return new RequestVoteResponse(request.term(), false);
        }
        return nodes.get(targetId).onRequestVote(request);
    }

    @Override
    public synchronized AppendEntriesResponse appendEntries(String targetId, AppendEntriesRequest request) {
        if (!canDeliver(request.leaderId(), targetId)) {
            return new AppendEntriesResponse(request.term(), false, 0);
        }
        return nodes.get(targetId).onAppendEntries(request);
    }

    public synchronized void tickAll() {
        runningNodes().forEach(RaftNode::tick);
    }

    public synchronized void runTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tickAll();
        }
    }

    public synchronized Optional<RaftNode> runUntilLeaderElected(int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            Optional<RaftNode> leader = leader();
            if (leader.isPresent()) {
                return leader;
            }
            tickAll();
        }
        return leader();
    }

    public synchronized Optional<RaftNode> leader() {
        return runningNodes().stream()
                .filter(node -> node.role() == RaftRole.LEADER)
                .max(Comparator.comparingInt(RaftNode::currentTerm));
    }

    public synchronized String leaderId() {
        return leader().map(RaftNode::id).orElse(null);
    }

    public synchronized ClientResponse propose(String command) {
        return leader()
                .map(raftNode -> raftNode.propose(command))
                .orElseGet(() -> ClientResponse.failed(null, 0, -1, "no leader elected"));
    }

    public synchronized void stop(String nodeId) {
        stoppedNodes.add(nodeId);
    }

    public synchronized void start(String nodeId) {
        stoppedNodes.remove(nodeId);
        nodes.get(nodeId).restartElectionTimer();
    }

    public synchronized void isolate(String nodeId) {
        for (String other : nodes.keySet()) {
            if (!other.equals(nodeId)) {
                block(nodeId, other);
                block(other, nodeId);
            }
        }
    }

    public synchronized void heal(String nodeId) {
        blockedLinks.removeIf(link -> link.startsWith(nodeId + "->") || link.endsWith("->" + nodeId));
    }

    public synchronized RaftNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public synchronized Collection<RaftNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public synchronized String describe() {
        StringJoiner joiner = new StringJoiner(System.lineSeparator());
        nodes.values().stream()
                .sorted(Comparator.comparing(RaftNode::id))
                .forEach(node -> joiner.add(String.format(
                        "%s %-9s term=%d leader=%s commit=%d state=%s log=%s%s",
                        node.id(),
                        node.role(),
                        node.currentTerm(),
                        node.leaderId(),
                        node.commitIndex(),
                        node.stateSnapshot(),
                        node.logSummary(),
                        stoppedNodes.contains(node.id()) ? " STOPPED" : ""
                )));
        return joiner.toString();
    }

    private List<RaftNode> runningNodes() {
        return nodes.values().stream()
                .filter(node -> !stoppedNodes.contains(node.id()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private void block(String from, String to) {
        blockedLinks.add(linkKey(from, to));
    }

    private boolean canDeliver(String from, String to) {
        return nodes.containsKey(from)
                && nodes.containsKey(to)
                && !stoppedNodes.contains(from)
                && !stoppedNodes.contains(to)
                && !blockedLinks.contains(linkKey(from, to));
    }

    private String linkKey(String from, String to) {
        return from + "->" + to;
    }
}

package com.example.kafka.replication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LogReplicationDemo {
    public static void run() {
        System.out.println("\n========== 日志复制：leader/follower、ISR、High Watermark ==========");

        ReplicaSet partition = new ReplicaSet("orders-0", List.of(1, 2, 3), 1, 2);

        long offset0 = partition.appendAsLeader("order-created");
        partition.fetchFromLeader(2, 10);
        partition.fetchFromLeader(3, 10);
        System.out.printf("写入offset=%d，所有副本同步后HW=%d，已提交=%s%n",
                offset0, partition.highWatermark(), partition.isCommitted(offset0));

        long offset1 = partition.appendAsLeader("order-paid");
        partition.fetchFromLeader(2, 10);
        System.out.printf("副本3落后时offset=%d，ISR=%s，HW=%d，已提交=%s%n",
                offset1, partition.isr(), partition.highWatermark(), partition.isCommitted(offset1));

        partition.shrinkIsrByLag(0);
        System.out.printf("副本3超过lag阈值被踢出ISR：ISR=%s，HW=%d，offset=%d已提交=%s%n",
                partition.isr(), partition.highWatermark(), offset1, partition.isCommitted(offset1));

        long offset2 = partition.appendAsLeader("order-shipped");
        partition.fetchFromLeader(2, 10);
        System.out.printf("acks=all要求min.insync.replicas=2：offset=%d，ISR=%s，HW=%d%n",
                offset2, partition.isr(), partition.highWatermark());

        partition.failLeaderAndElect(2);
        System.out.printf("leader 1故障，从ISR选举leader=%d，新leader日志=%s%n",
                partition.leaderId(), partition.leaderLogValues());
    }

    private static class ReplicaSet {
        private final String topicPartition;
        private final Map<Integer, ReplicaLog> replicas = new LinkedHashMap<>();
        private final Set<Integer> isr = new LinkedHashSet<>();
        private final int minInsyncReplicas;
        private int leaderId;
        private long nextOffset;
        private long highWatermark = -1;

        ReplicaSet(String topicPartition, List<Integer> replicaIds, int leaderId, int minInsyncReplicas) {
            this.topicPartition = topicPartition;
            this.leaderId = leaderId;
            this.minInsyncReplicas = minInsyncReplicas;
            for (Integer replicaId : replicaIds) {
                replicas.put(replicaId, new ReplicaLog(replicaId));
                isr.add(replicaId);
            }
        }

        long appendAsLeader(String value) {
            if (isr.size() < minInsyncReplicas) {
                throw new IllegalStateException("ISR数量不足，acks=all写入被拒绝：" + topicPartition);
            }
            RecordEntry entry = new RecordEntry(nextOffset++, value);
            leader().append(entry);
            recomputeHighWatermark();
            return entry.offset();
        }

        void fetchFromLeader(int followerId, int maxRecords) {
            if (followerId == leaderId) {
                return;
            }
            ReplicaLog follower = replicas.get(followerId);
            if (follower == null) {
                throw new IllegalArgumentException("未知副本：" + followerId);
            }

            List<RecordEntry> leaderEntries = leader().entries();
            long followerEndOffset = follower.endOffset();
            int copied = 0;
            while (followerEndOffset < leaderEntries.size() && copied < maxRecords) {
                follower.append(leaderEntries.get((int) followerEndOffset));
                followerEndOffset++;
                copied++;
            }
            recomputeHighWatermark();
        }

        void shrinkIsrByLag(long maxLag) {
            long leaderEndOffset = leader().endOffset();
            List<Integer> laggingReplicas = isr.stream()
                    .filter(replicaId -> replicaId != leaderId)
                    .filter(replicaId -> leaderEndOffset - replicas.get(replicaId).endOffset() > maxLag)
                    .toList();
            isr.removeAll(laggingReplicas);
            recomputeHighWatermark();
        }

        void failLeaderAndElect(int newLeaderId) {
            if (!isr.contains(newLeaderId)) {
                throw new IllegalArgumentException("只能从ISR中选举新leader，candidate=" + newLeaderId);
            }
            isr.remove(leaderId);
            leaderId = newLeaderId;
            truncateReplicasToHighWatermark();
            recomputeHighWatermark();
        }

        int leaderId() {
            return leaderId;
        }

        Set<Integer> isr() {
            return new LinkedHashSet<>(isr);
        }

        long highWatermark() {
            return highWatermark;
        }

        boolean isCommitted(long offset) {
            return offset <= highWatermark;
        }

        List<String> leaderLogValues() {
            return leader().entries().stream().map(RecordEntry::value).toList();
        }

        private ReplicaLog leader() {
            return replicas.get(leaderId);
        }

        private void recomputeHighWatermark() {
            highWatermark = isr.stream()
                    .map(replicas::get)
                    .mapToLong(ReplicaLog::endOffset)
                    .min()
                    .orElse(0) - 1;
            replicas.values().forEach(replica -> replica.setHighWatermark(highWatermark));
        }

        private void truncateReplicasToHighWatermark() {
            replicas.values().forEach(replica -> replica.truncateTo(highWatermark + 1));
            nextOffset = leader().endOffset();
        }
    }

    private static class ReplicaLog {
        private final int brokerId;
        private final List<RecordEntry> entries = new ArrayList<>();
        private long highWatermark = -1;

        ReplicaLog(int brokerId) {
            this.brokerId = brokerId;
        }

        void append(RecordEntry entry) {
            if (entry.offset() != entries.size()) {
                throw new IllegalStateException("副本" + brokerId + "日志offset不连续");
            }
            entries.add(entry);
        }

        long endOffset() {
            return entries.size();
        }

        List<RecordEntry> entries() {
            return entries;
        }

        void setHighWatermark(long highWatermark) {
            this.highWatermark = highWatermark;
        }

        void truncateTo(long endOffset) {
            while (entries.size() > endOffset) {
                entries.remove(entries.size() - 1);
            }
            if (highWatermark >= entries.size()) {
                highWatermark = entries.size() - 1L;
            }
        }
    }

    private record RecordEntry(long offset, String value) {
    }
}

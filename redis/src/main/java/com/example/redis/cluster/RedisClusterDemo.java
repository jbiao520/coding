package com.example.redis.cluster;

import com.example.redis.common.RedisNode;
import com.example.redis.common.SlotHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RedisClusterDemo {
    public static void run() {
        System.out.println("\n=== Redis Cluster: 16384 slot + replica failover ===");

        RedisCluster cluster = RedisCluster.create(
                new SlotRange(0, 5_460),
                new SlotRange(5_461, 10_922),
                new SlotRange(10_923, SlotHash.CLUSTER_SLOTS - 1)
        );

        cluster.put("order:{1001}", "paid");
        cluster.put("cart:{1001}", "2 items");
        cluster.put("profile:42", "Ada");
        cluster.replicateAll();

        cluster.printTopology("initial topology");
        cluster.printKeyRoute("order:{1001}");
        cluster.printKeyRoute("profile:42");

        String failedMaster = cluster.masterFor("order:{1001}").id();
        System.out.println("fail master " + failedMaster + ", cluster promotes its replica");
        cluster.failMaster(failedMaster);
        cluster.printTopology("after failover");

        cluster.put("order:{1001}", "shipped");
        cluster.replicateAll();
        System.out.println("read after failover order:{1001} = " + cluster.get("order:{1001}").orElse("<nil>"));
    }

    private static class RedisCluster {
        private final List<Shard> shards;
        private final Map<String, RedisNode> nodesById;

        private RedisCluster(List<Shard> shards) {
            this.shards = shards;
            this.nodesById = shards.stream()
                    .flatMap(shard -> shard.nodes().stream())
                    .collect(Collectors.toMap(RedisNode::id, node -> node, (left, right) -> left, LinkedHashMap::new));
        }

        static RedisCluster create(SlotRange... ranges) {
            List<Shard> shards = new ArrayList<>();
            for (int i = 0; i < ranges.length; i++) {
                RedisNode master = new RedisNode("cluster-m" + (i + 1), RedisNode.Role.MASTER);
                RedisNode replica = new RedisNode("cluster-r" + (i + 1), RedisNode.Role.REPLICA);
                replica.replicateFrom(master);
                shards.add(new Shard(ranges[i], master, List.of(replica)));
            }
            return new RedisCluster(shards);
        }

        void put(String key, String value) {
            masterFor(key).write(key, value);
        }

        Optional<String> get(String key) {
            Shard shard = shardFor(key);
            RedisNode master = shard.master();
            if (master.isAlive()) {
                return master.read(key);
            }
            return shard.replicas().stream()
                    .filter(RedisNode::isAlive)
                    .findFirst()
                    .flatMap(replica -> replica.read(key));
        }

        RedisNode masterFor(String key) {
            return shardFor(key).master();
        }

        void replicateAll() {
            shards.stream()
                    .flatMap(shard -> shard.replicas().stream())
                    .filter(RedisNode::isAlive)
                    .forEach(RedisNode::syncFromMaster);
        }

        void failMaster(String masterId) {
            RedisNode failed = nodesById.get(masterId);
            if (failed == null || !failed.isMaster()) {
                throw new IllegalArgumentException("unknown master: " + masterId);
            }
            failed.fail();

            Shard shard = shards.stream()
                    .filter(candidate -> candidate.master().id().equals(masterId))
                    .findFirst()
                    .orElseThrow();
            RedisNode promoted = shard.replicas().stream()
                    .filter(RedisNode::isAlive)
                    .min(Comparator.comparing(RedisNode::id))
                    .orElseThrow(() -> new IllegalStateException("no alive replica for " + masterId));
            promoted.promoteToMaster();
            shard.replaceMaster(promoted);
        }

        void printKeyRoute(String key) {
            int slot = SlotHash.clusterSlot(key);
            System.out.println("key " + key + " -> slot " + slot + " -> " + masterFor(key).id());
        }

        void printTopology(String title) {
            System.out.println(title);
            for (Shard shard : shards) {
                System.out.println("  " + shard);
            }
        }

        private Shard shardFor(String key) {
            int slot = SlotHash.clusterSlot(key);
            return shards.stream()
                    .filter(shard -> shard.range().contains(slot))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("slot not covered: " + slot));
        }
    }

    private static class Shard {
        private final SlotRange range;
        private RedisNode master;
        private final List<RedisNode> replicas;

        Shard(SlotRange range, RedisNode master, List<RedisNode> replicas) {
            this.range = range;
            this.master = master;
            this.replicas = new ArrayList<>(replicas);
        }

        SlotRange range() {
            return range;
        }

        RedisNode master() {
            return master;
        }

        List<RedisNode> replicas() {
            return replicas;
        }

        List<RedisNode> nodes() {
            List<RedisNode> nodes = new ArrayList<>();
            nodes.add(master);
            nodes.addAll(replicas);
            return nodes;
        }

        void replaceMaster(RedisNode promoted) {
            master.replicateFrom(promoted);
            replicas.remove(promoted);
            replicas.add(master);
            master = promoted;
        }

        @Override
        public String toString() {
            return range + " master=" + master + " replicas=" + replicas;
        }
    }

    private record SlotRange(int startInclusive, int endInclusive) {
        boolean contains(int slot) {
            return slot >= startInclusive && slot <= endInclusive;
        }

        @Override
        public String toString() {
            return "[" + startInclusive + "-" + endInclusive + "]";
        }
    }
}

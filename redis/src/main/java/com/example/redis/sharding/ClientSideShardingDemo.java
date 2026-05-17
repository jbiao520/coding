package com.example.redis.sharding;

import com.example.redis.common.RedisNode;
import com.example.redis.common.SlotHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public class ClientSideShardingDemo {
    private static final List<String> SAMPLE_KEYS = List.of("product:1", "product:20", "product:118");

    public static void run() {
        System.out.println("\n=== Client-side Sharding: consistent hash + standby shard ===");

        ShardedRedis shardedRedis = ShardedRedis.create("shard-a", "shard-b", "shard-c");
        shardedRedis.put("product:1", "keyboard");
        shardedRedis.put("product:20", "monitor");
        shardedRedis.put("product:118", "mouse");
        shardedRedis.replicateAll();

        shardedRedis.printRoutes("before shard failure");

        String failedShard = shardedRedis.primaryFor("product:1").id();
        System.out.println("primary shard " + failedShard + " is down, client routes reads to standby");
        shardedRedis.failPrimary(failedShard);

        System.out.println("read product:1 = " + shardedRedis.get("product:1").orElse("<nil>"));
        shardedRedis.put("product:1", "mechanical keyboard");
        shardedRedis.replicateAll();
        shardedRedis.printRoutes("after standby promotion");
    }

    private static class ShardedRedis {
        private final List<ShardPair> shards;
        private final ConsistentHashRing hashRing = new ConsistentHashRing();

        private ShardedRedis(List<ShardPair> shards) {
            this.shards = shards;
            shards.forEach(hashRing::add);
        }

        static ShardedRedis create(String... shardNames) {
            List<ShardPair> shards = new ArrayList<>();
            for (String shardName : shardNames) {
                RedisNode primary = new RedisNode(shardName + "-primary", RedisNode.Role.MASTER);
                RedisNode standby = new RedisNode(shardName + "-standby", RedisNode.Role.REPLICA);
                standby.replicateFrom(primary);
                shards.add(new ShardPair(shardName, primary, standby));
            }
            return new ShardedRedis(shards);
        }

        void put(String key, String value) {
            primaryFor(key).write(key, value);
        }

        Optional<String> get(String key) {
            ShardPair shard = shardFor(key);
            if (shard.primary().isAlive()) {
                return shard.primary().read(key);
            }
            return shard.standby().read(key);
        }

        RedisNode primaryFor(String key) {
            return shardFor(key).primary();
        }

        void failPrimary(String nodeId) {
            ShardPair shard = shards.stream()
                    .filter(candidate -> candidate.primary().id().equals(nodeId))
                    .findFirst()
                    .orElseThrow();
            shard.primary().fail();
            shard.promoteStandby();
        }

        void replicateAll() {
            shards.stream()
                    .map(ShardPair::standby)
                    .filter(RedisNode::isAlive)
                    .forEach(RedisNode::syncFromMaster);
        }

        void printRoutes(String title) {
            System.out.println(title);
            for (String key : SAMPLE_KEYS) {
                System.out.println("  " + key + " -> " + primaryFor(key));
            }
        }

        private ShardPair shardFor(String key) {
            ShardPair owner = hashRing.route(key);
            return shards.stream()
                    .filter(shard -> shard == owner)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static class ShardPair {
        private final String shardId;
        private RedisNode primary;
        private RedisNode standby;

        ShardPair(String shardId, RedisNode primary, RedisNode standby) {
            this.shardId = shardId;
            this.primary = primary;
            this.standby = standby;
        }

        String shardId() {
            return shardId;
        }

        RedisNode primary() {
            return primary;
        }

        RedisNode standby() {
            return standby;
        }

        void promoteStandby() {
            RedisNode oldPrimary = primary;
            standby.promoteToMaster();
            primary = standby;
            standby = oldPrimary;
            standby.replicateFrom(primary);
            standby.fail();
        }
    }

    private static class ConsistentHashRing {
        private static final int VIRTUAL_NODES = 32;
        private final TreeMap<Integer, ShardPair> ring = new TreeMap<>();

        void add(ShardPair shard) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                ring.put(SlotHash.stableHash(shard.shardId() + "#" + i), shard);
            }
        }

        ShardPair route(String key) {
            if (ring.isEmpty()) {
                throw new IllegalStateException("no shard available");
            }
            int hash = SlotHash.stableHash(key);
            Map.Entry<Integer, ShardPair> entry = ring.ceilingEntry(hash);
            return entry == null ? ring.firstEntry().getValue() : entry.getValue();
        }
    }
}

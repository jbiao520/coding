package com.example.redis.sentinel;

import com.example.redis.common.RedisNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class RedisSentinelDemo {
    public static void run() {
        System.out.println("\n=== Redis Sentinel: monitor + quorum + master switch ===");

        SentinelDeployment deployment = SentinelDeployment.withThreeSentinels();
        deployment.write("session:1", "user-42");
        deployment.replicateAll();
        deployment.printState("initial master-replica");

        deployment.master().fail();
        System.out.println("master " + deployment.master().id() + " is unreachable");

        deployment.detectAndFailover();
        deployment.printState("after sentinel failover");

        deployment.write("session:2", "user-84");
        deployment.replicateAll();
        System.out.println("read session:1 = " + deployment.read("session:1").orElse("<nil>"));
        System.out.println("read session:2 = " + deployment.read("session:2").orElse("<nil>"));
    }

    private static class SentinelDeployment {
        private RedisNode master;
        private final List<RedisNode> replicas;
        private final List<Sentinel> sentinels;
        private final int quorum;

        private SentinelDeployment(RedisNode master, List<RedisNode> replicas, List<Sentinel> sentinels, int quorum) {
            this.master = master;
            this.replicas = new ArrayList<>(replicas);
            this.sentinels = sentinels;
            this.quorum = quorum;
        }

        static SentinelDeployment withThreeSentinels() {
            RedisNode master = new RedisNode("sentinel-master", RedisNode.Role.MASTER);
            RedisNode replica1 = new RedisNode("sentinel-replica-a", RedisNode.Role.REPLICA);
            RedisNode replica2 = new RedisNode("sentinel-replica-b", RedisNode.Role.REPLICA);
            replica1.replicateFrom(master);
            replica2.replicateFrom(master);
            return new SentinelDeployment(
                    master,
                    List.of(replica1, replica2),
                    List.of(new Sentinel("s1"), new Sentinel("s2"), new Sentinel("s3")),
                    2
            );
        }

        RedisNode master() {
            return master;
        }

        void write(String key, String value) {
            master.write(key, value);
        }

        Optional<String> read(String key) {
            if (master.isAlive()) {
                return master.read(key);
            }
            return replicas.stream()
                    .filter(RedisNode::isAlive)
                    .findFirst()
                    .flatMap(replica -> replica.read(key));
        }

        void replicateAll() {
            replicas.stream()
                    .filter(RedisNode::isAlive)
                    .filter(replica -> replica.replicatingFrom().filter(RedisNode::isAlive).isPresent())
                    .forEach(RedisNode::syncFromMaster);
        }

        void detectAndFailover() {
            long votes = sentinels.stream()
                    .filter(sentinel -> sentinel.seesMasterDown(master))
                    .peek(sentinel -> System.out.println("  " + sentinel.id() + " marks master subjective down"))
                    .count();
            if (votes < quorum) {
                System.out.println("quorum not reached, no failover");
                return;
            }

            RedisNode promoted = replicas.stream()
                    .filter(RedisNode::isAlive)
                    .max(Comparator.comparingInt(replica -> replica.snapshot().size()))
                    .orElseThrow(() -> new IllegalStateException("no replica can be promoted"));

            RedisNode oldMaster = master;
            replicas.remove(promoted);
            promoted.promoteToMaster();
            master = promoted;

            oldMaster.replicateFrom(master);
            replicas.add(oldMaster);
            replicas.stream()
                    .filter(replica -> replica != oldMaster)
                    .filter(RedisNode::isAlive)
                    .forEach(replica -> replica.replicateFrom(master));

            System.out.println("quorum=" + votes + "/" + sentinels.size() + ", promoted " + promoted.id());
        }

        void printState(String title) {
            System.out.println(title);
            System.out.println("  master=" + master);
            System.out.println("  replicas=" + replicas);
        }
    }

    private record Sentinel(String id) {
        boolean seesMasterDown(RedisNode master) {
            return !master.isAlive();
        }
    }
}

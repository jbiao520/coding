package com.example.kafka.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ControllerElectionDemo {
    public static void run() {
        System.out.println("\n========== Controller选举：epoch、fencing、元数据变更 ==========");

        ControllerCluster cluster = new ControllerCluster();
        cluster.registerBroker(1, "rack-a");
        cluster.registerBroker(2, "rack-b");
        cluster.registerBroker(3, "rack-c");

        ControllerCandidate first = cluster.electController();
        System.out.printf("首次选举：controller=%d，controllerEpoch=%d%n", first.brokerId(), first.epoch());

        cluster.createTopic("orders", 3, 3);
        cluster.printMetadata("创建topic后的元数据");

        System.out.printf("旧controller携带epoch=0提交请求，被fence=%s%n",
                !cluster.acceptControllerWrite(first.brokerId(), 0));

        cluster.failBroker(first.brokerId());
        ControllerCandidate second = cluster.electController();
        System.out.printf("controller故障后重新选举：controller=%d，controllerEpoch=%d%n",
                second.brokerId(), second.epoch());

        cluster.moveLeadershipOffDeadBrokers();
        cluster.printMetadata("故障转移后的元数据");
    }

    private static class ControllerCluster {
        private final Map<Integer, Broker> brokers = new LinkedHashMap<>();
        private final MetadataImage metadata = new MetadataImage();
        private int controllerEpoch;
        private Integer activeControllerId;

        void registerBroker(int brokerId, String rack) {
            brokers.put(brokerId, new Broker(brokerId, rack));
        }

        ControllerCandidate electController() {
            if (activeControllerId != null && isLive(activeControllerId)) {
                return new ControllerCandidate(activeControllerId, controllerEpoch);
            }

            Broker next = brokers.values().stream()
                    .filter(Broker::live)
                    .min(Comparator.comparingInt(Broker::id))
                    .orElseThrow(() -> new IllegalStateException("没有存活broker，无法选举controller"));

            controllerEpoch++;
            activeControllerId = next.id();
            ControllerCandidate candidate = new ControllerCandidate(next.id(), controllerEpoch);
            brokers.values().forEach(broker -> broker.acceptController(candidate));
            return candidate;
        }

        boolean acceptControllerWrite(int controllerId, int epoch) {
            return Objects.equals(activeControllerId, controllerId) && epoch == controllerEpoch;
        }

        void createTopic(String topic, int partitions, int replicationFactor) {
            ensureActiveController();
            List<Integer> liveBrokerIds = liveBrokerIds();
            if (liveBrokerIds.size() < replicationFactor) {
                throw new IllegalArgumentException("副本数不能大于存活broker数");
            }

            for (int partition = 0; partition < partitions; partition++) {
                List<Integer> replicas = new ArrayList<>();
                for (int replica = 0; replica < replicationFactor; replica++) {
                    replicas.add(liveBrokerIds.get((partition + replica) % liveBrokerIds.size()));
                }
                metadata.put(new PartitionRegistration(topic, partition, replicas.get(0), replicas, controllerEpoch, 0));
            }
        }

        void failBroker(int brokerId) {
            Broker broker = brokers.get(brokerId);
            if (broker != null) {
                broker.shutdown();
            }
            if (Objects.equals(activeControllerId, brokerId)) {
                activeControllerId = null;
            }
        }

        void moveLeadershipOffDeadBrokers() {
            ensureActiveController();
            for (PartitionRegistration partition : metadata.partitions()) {
                if (isLive(partition.leader())) {
                    continue;
                }

                int newLeader = partition.replicas().stream()
                        .filter(this::isLive)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("分区没有存活副本：" + partition.topicPartition()));
                metadata.put(partition.withLeader(newLeader, partition.leaderEpoch() + 1, controllerEpoch));
            }
        }

        void printMetadata(String title) {
            System.out.println(title + "：");
            for (PartitionRegistration partition : metadata.partitions()) {
                System.out.printf("  %s leader=%d replicas=%s leaderEpoch=%d controllerEpoch=%d%n",
                        partition.topicPartition(),
                        partition.leader(),
                        partition.replicas(),
                        partition.leaderEpoch(),
                        partition.controllerEpoch());
            }
        }

        private void ensureActiveController() {
            if (activeControllerId == null || !isLive(activeControllerId)) {
                throw new IllegalStateException("没有活跃controller");
            }
        }

        private boolean isLive(int brokerId) {
            Broker broker = brokers.get(brokerId);
            return broker != null && broker.live();
        }

        private List<Integer> liveBrokerIds() {
            return brokers.values().stream()
                    .filter(Broker::live)
                    .map(Broker::id)
                    .sorted()
                    .toList();
        }
    }

    private static class Broker {
        private final int id;
        private final String rack;
        private boolean live = true;
        private int controllerEpochSeen;

        Broker(int id, String rack) {
            this.id = id;
            this.rack = rack;
        }

        int id() {
            return id;
        }

        boolean live() {
            return live;
        }

        void shutdown() {
            live = false;
        }

        boolean acceptController(ControllerCandidate candidate) {
            if (candidate.epoch() < controllerEpochSeen) {
                return false;
            }
            controllerEpochSeen = candidate.epoch();
            return true;
        }

        @Override
        public String toString() {
            return "broker-" + id + "(" + rack + ")";
        }
    }

    private record ControllerCandidate(int brokerId, int epoch) {
    }

    private static class MetadataImage {
        private final Map<String, PartitionRegistration> partitions = new LinkedHashMap<>();

        void put(PartitionRegistration registration) {
            partitions.put(registration.topicPartition(), registration);
        }

        List<PartitionRegistration> partitions() {
            return new ArrayList<>(partitions.values());
        }
    }

    private record PartitionRegistration(
            String topic,
            int partition,
            int leader,
            List<Integer> replicas,
            int controllerEpoch,
            int leaderEpoch
    ) {
        String topicPartition() {
            return topic + "-" + partition;
        }

        PartitionRegistration withLeader(int newLeader, int newLeaderEpoch, int newControllerEpoch) {
            return new PartitionRegistration(topic, partition, newLeader, replicas, newControllerEpoch, newLeaderEpoch);
        }
    }
}

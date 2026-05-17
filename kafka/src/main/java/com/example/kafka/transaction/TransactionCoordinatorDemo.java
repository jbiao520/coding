package com.example.kafka.transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TransactionCoordinatorDemo {
    public static void run() {
        System.out.println("\n========== 事务协调：producer epoch、状态机、commit/abort marker ==========");

        TransactionCoordinator coordinator = new TransactionCoordinator();
        TransactionalPartition orders = new TransactionalPartition("orders-0");
        TransactionalPartition payments = new TransactionalPartition("payments-0");

        ProducerIdentity producer = coordinator.initProducerId("checkout-service");
        coordinator.beginTransaction(producer);
        coordinator.send(producer, orders, "order-1", "created");
        coordinator.send(producer, payments, "payment-1", "reserved");

        System.out.printf("事务提交前 read_uncommitted=%s，read_committed=%s%n",
                orders.readUncommitted(), orders.readCommitted());

        coordinator.commit(producer);
        System.out.printf("事务提交后 orders read_committed=%s，payments read_committed=%s%n",
                orders.readCommitted(), payments.readCommitted());

        ProducerIdentity restartedProducer = coordinator.initProducerId("checkout-service");
        try {
            coordinator.beginTransaction(producer);
        } catch (ProducerFencedException e) {
            System.out.println("旧producer实例被新epoch fence：" + e.getMessage());
        }

        coordinator.beginTransaction(restartedProducer);
        coordinator.send(restartedProducer, orders, "order-2", "created");
        coordinator.abort(restartedProducer);
        System.out.printf("事务abort后 read_uncommitted=%s，read_committed=%s%n",
                orders.readUncommitted(), orders.readCommitted());
    }

    private static class TransactionCoordinator {
        private final Map<String, TransactionMetadata> transactions = new LinkedHashMap<>();
        private long nextProducerId = 1000;

        ProducerIdentity initProducerId(String transactionalId) {
            TransactionMetadata metadata = transactions.get(transactionalId);
            if (metadata == null) {
                metadata = new TransactionMetadata(transactionalId, nextProducerId++, (short) 0);
                transactions.put(transactionalId, metadata);
            } else {
                metadata.bumpProducerEpoch();
            }
            metadata.transitionTo(TransactionState.EMPTY);
            return new ProducerIdentity(transactionalId, metadata.producerId(), metadata.producerEpoch());
        }

        void beginTransaction(ProducerIdentity producer) {
            TransactionMetadata metadata = requireCurrentProducer(producer);
            metadata.clearPartitions();
            metadata.beginNewTransaction();
        }

        void send(ProducerIdentity producer, TransactionalPartition partition, String key, String value) {
            TransactionMetadata metadata = requireCurrentProducer(producer);
            if (metadata.state() != TransactionState.ONGOING) {
                throw new IllegalStateException("事务未开始，不能写入消息");
            }
            partition.appendData(producer.producerId(), metadata.currentTransactionKey(), producer.transactionalId(), key, value);
            metadata.addPartition(partition);
        }

        void commit(ProducerIdentity producer) {
            complete(producer, ControlType.COMMIT);
        }

        void abort(ProducerIdentity producer) {
            complete(producer, ControlType.ABORT);
        }

        private void complete(ProducerIdentity producer, ControlType marker) {
            TransactionMetadata metadata = requireCurrentProducer(producer);
            if (metadata.state() != TransactionState.ONGOING) {
                throw new IllegalStateException("事务状态不允许结束：" + metadata.state());
            }

            metadata.transitionTo(marker == ControlType.COMMIT
                    ? TransactionState.PREPARE_COMMIT
                    : TransactionState.PREPARE_ABORT);
            for (TransactionalPartition partition : metadata.partitions()) {
                partition.appendControl(producer.producerId(), metadata.currentTransactionKey(), producer.transactionalId(), marker);
            }
            metadata.transitionTo(marker == ControlType.COMMIT
                    ? TransactionState.COMPLETE_COMMIT
                    : TransactionState.COMPLETE_ABORT);
        }

        private TransactionMetadata requireCurrentProducer(ProducerIdentity producer) {
            TransactionMetadata metadata = transactions.get(producer.transactionalId());
            if (metadata == null || metadata.producerId() != producer.producerId()
                    || metadata.producerEpoch() != producer.producerEpoch()) {
                throw new ProducerFencedException("transactional.id=" + producer.transactionalId()
                        + " producerId=" + producer.producerId()
                        + " epoch=" + producer.producerEpoch());
            }
            return metadata;
        }
    }

    private static class TransactionMetadata {
        private final String transactionalId;
        private final long producerId;
        private final Set<TransactionalPartition> partitions = new LinkedHashSet<>();
        private short producerEpoch;
        private int transactionSequence;
        private String currentTransactionKey;
        private TransactionState state = TransactionState.EMPTY;

        TransactionMetadata(String transactionalId, long producerId, short producerEpoch) {
            this.transactionalId = transactionalId;
            this.producerId = producerId;
            this.producerEpoch = producerEpoch;
        }

        long producerId() {
            return producerId;
        }

        short producerEpoch() {
            return producerEpoch;
        }

        TransactionState state() {
            return state;
        }

        void bumpProducerEpoch() {
            producerEpoch++;
        }

        void beginNewTransaction() {
            transactionSequence++;
            currentTransactionKey = transactionalId + "#" + producerId + "#" + producerEpoch + "#" + transactionSequence;
            transitionTo(TransactionState.ONGOING);
        }

        String currentTransactionKey() {
            return currentTransactionKey;
        }

        void transitionTo(TransactionState next) {
            if (!state.canTransitionTo(next)) {
                throw new IllegalStateException(transactionalId + "非法事务状态流转：" + state + " -> " + next);
            }
            state = next;
        }

        void addPartition(TransactionalPartition partition) {
            partitions.add(partition);
        }

        Set<TransactionalPartition> partitions() {
            return new LinkedHashSet<>(partitions);
        }

        void clearPartitions() {
            partitions.clear();
        }
    }

    private static class TransactionalPartition {
        private final String topicPartition;
        private final List<LogEntry> log = new ArrayList<>();

        TransactionalPartition(String topicPartition) {
            this.topicPartition = topicPartition;
        }

        void appendData(long producerId, String transactionKey, String transactionalId, String key, String value) {
            log.add(LogEntry.data(log.size(), producerId, transactionKey, transactionalId, key, value));
        }

        void appendControl(long producerId, String transactionKey, String transactionalId, ControlType controlType) {
            log.add(LogEntry.control(log.size(), producerId, transactionKey, transactionalId, controlType));
        }

        List<String> readUncommitted() {
            return log.stream()
                    .filter(LogEntry::data)
                    .map(LogEntry::recordView)
                    .toList();
        }

        List<String> readCommitted() {
            Map<String, ControlType> transactionOutcome = new LinkedHashMap<>();
            for (LogEntry entry : log) {
                if (!entry.data()) {
                    transactionOutcome.put(entry.transactionKey(), entry.controlType());
                }
            }

            return log.stream()
                    .filter(LogEntry::data)
                    .filter(entry -> transactionOutcome.get(entry.transactionKey()) == ControlType.COMMIT)
                    .map(LogEntry::recordView)
                    .toList();
        }

        @Override
        public String toString() {
            return topicPartition;
        }
    }

    private record ProducerIdentity(String transactionalId, long producerId, short producerEpoch) {
    }

    private record LogEntry(
            long offset,
            long producerId,
            String transactionKey,
            String transactionalId,
            String key,
            String value,
            ControlType controlType
    ) {
        static LogEntry data(long offset, long producerId, String transactionKey, String transactionalId, String key, String value) {
            return new LogEntry(offset, producerId, transactionKey, transactionalId, key, value, null);
        }

        static LogEntry control(long offset, long producerId, String transactionKey, String transactionalId, ControlType controlType) {
            return new LogEntry(offset, producerId, transactionKey, transactionalId, null, null, controlType);
        }

        boolean data() {
            return controlType == null;
        }

        String recordView() {
            return offset + ":" + key + "=" + value;
        }
    }

    private enum ControlType {
        COMMIT,
        ABORT
    }

    private enum TransactionState {
        EMPTY,
        ONGOING,
        PREPARE_COMMIT,
        PREPARE_ABORT,
        COMPLETE_COMMIT,
        COMPLETE_ABORT;

        boolean canTransitionTo(TransactionState next) {
            return switch (this) {
                case EMPTY -> next == ONGOING || next == EMPTY;
                case ONGOING -> next == PREPARE_COMMIT || next == PREPARE_ABORT || next == EMPTY;
                case PREPARE_COMMIT -> next == COMPLETE_COMMIT;
                case PREPARE_ABORT -> next == COMPLETE_ABORT;
                case COMPLETE_COMMIT, COMPLETE_ABORT -> next == EMPTY;
            };
        }
    }

    private static class ProducerFencedException extends RuntimeException {
        ProducerFencedException(String message) {
            super(message);
        }
    }
}

package com.example.kafka.transaction;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class OrderPaymentTransactionDemo {
    private static final String PAYMENT_COMMANDS = "payment.commands";
    private static final String ORDER_EVENTS = "order.events";
    private static final String PAYMENT_EVENTS = "payment.events";
    private static final String LEDGER_EVENTS = "accounting.ledger";
    private static final String AUDIT_EVENTS = "risk.audit";

    public static void run() {
        System.out.println("\n========== 订单支付分布式事务方案：Kafka事务API、幂等生产者、read_committed ==========");
        System.out.println("输入Topic：" + PAYMENT_COMMANDS + "，输出Topics："
                + List.of(ORDER_EVENTS, PAYMENT_EVENTS, LEDGER_EVENTS, AUDIT_EVENTS));
        System.out.println("事务边界：支付指令offset提交 + 订单事件 + 支付事件 + 账务流水 + 审计事件 同一Kafka事务提交。");
        System.out.println("跨Topic/跨分区原子性：同一个transactional.id绑定producerId/epoch，由事务协调器写入commit/abort marker。");
        System.out.println("外部支付网关不属于Kafka事务：必须使用paymentAttemptId作为幂等键，失败重试时先查询/复用支付结果。");
        System.out.println("下游消费者必须设置 isolation.level=read_committed，避免读到未提交或已abort的事务消息。");
    }

    public static Properties producerProperties(String bootstrapServers, String transactionalId) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);

        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 60_000);
        return props;
    }

    public static Properties consumerProperties(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return props;
    }

    public static final class PaymentTransactionProcessor implements AutoCloseable {
        private final KafkaProducer<String, String> producer;
        private final KafkaConsumer<String, String> consumer;
        private final PaymentGateway paymentGateway;

        public PaymentTransactionProcessor(String bootstrapServers,
                                           String instanceSlot,
                                           String groupId,
                                           PaymentGateway paymentGateway) {
            String transactionalId = "payment-service-" + instanceSlot;
            this.producer = new KafkaProducer<>(producerProperties(bootstrapServers, transactionalId));
            this.consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers, groupId));
            this.paymentGateway = paymentGateway;

            this.producer.initTransactions();
            this.consumer.subscribe(List.of(PAYMENT_COMMANDS));
        }

        public void pollAndProcessOnce(Duration timeout) {
            ConsumerRecords<String, String> records = consumer.poll(timeout);
            for (ConsumerRecord<String, String> record : records) {
                PaymentCommand command = PaymentCommand.from(record.key(), record.value());
                processOne(record, command);
            }
        }

        private void processOne(ConsumerRecord<String, String> record, PaymentCommand command) {
            PaymentResult paymentResult = paymentGateway.capture(command);

            try {
                producer.beginTransaction();
                producer.send(new ProducerRecord<>(
                        PAYMENT_EVENTS,
                        command.orderId(),
                        EventJson.paymentCaptured(command, paymentResult)
                ));
                producer.send(new ProducerRecord<>(
                        ORDER_EVENTS,
                        command.orderId(),
                        EventJson.orderPaid(command, paymentResult)
                ));
                producer.send(new ProducerRecord<>(
                        LEDGER_EVENTS,
                        command.merchantId(),
                        EventJson.ledgerEntry(command, paymentResult)
                ));
                producer.send(new ProducerRecord<>(
                        AUDIT_EVENTS,
                        command.paymentAttemptId(),
                        EventJson.audit(command, paymentResult)
                ));

                Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)
                );
                producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                producer.commitTransaction();
            } catch (RuntimeException commitFailure) {
                producer.abortTransaction();
                throw commitFailure;
            }
        }

        @Override
        public void close() {
            try {
                producer.close(Duration.ofSeconds(10));
            } finally {
                consumer.close(Duration.ofSeconds(10));
            }
        }
    }

    public interface PaymentGateway {
        PaymentResult capture(PaymentCommand command);
    }

    public record PaymentCommand(
            String orderId,
            String userId,
            String merchantId,
            String paymentAttemptId,
            long amountCent,
            String currency
    ) {
        static PaymentCommand from(String key, String payload) {
            String[] fields = payload.split("\\|");
            if (fields.length != 6) {
                throw new IllegalArgumentException("支付指令格式：orderId|userId|merchantId|paymentAttemptId|amountCent|currency");
            }
            if (!key.equals(fields[0])) {
                throw new IllegalArgumentException("消息key必须是orderId，保证同一订单进入同一分区");
            }
            return new PaymentCommand(fields[0], fields[1], fields[2], fields[3], Long.parseLong(fields[4]), fields[5]);
        }
    }

    public record PaymentResult(String providerTxnId, String status, long capturedAtEpochMillis) {
    }

    private static final class EventJson {
        private static String paymentCaptured(PaymentCommand command, PaymentResult result) {
            return """
                    {"eventType":"PaymentCaptured","orderId":"%s","paymentAttemptId":"%s","providerTxnId":"%s","status":"%s","amountCent":%d,"currency":"%s","capturedAt":%d}"""
                    .formatted(command.orderId(), command.paymentAttemptId(), result.providerTxnId(), result.status(),
                            command.amountCent(), command.currency(), result.capturedAtEpochMillis());
        }

        private static String orderPaid(PaymentCommand command, PaymentResult result) {
            return """
                    {"eventType":"OrderPaid","orderId":"%s","userId":"%s","paymentAttemptId":"%s","amountCent":%d,"currency":"%s","paidAt":%d}"""
                    .formatted(command.orderId(), command.userId(), command.paymentAttemptId(), command.amountCent(),
                            command.currency(), result.capturedAtEpochMillis());
        }

        private static String ledgerEntry(PaymentCommand command, PaymentResult result) {
            return """
                    {"eventType":"LedgerEntryCreated","merchantId":"%s","orderId":"%s","providerTxnId":"%s","debitCent":%d,"currency":"%s"}"""
                    .formatted(command.merchantId(), command.orderId(), result.providerTxnId(), command.amountCent(),
                            command.currency());
        }

        private static String audit(PaymentCommand command, PaymentResult result) {
            return """
                    {"eventType":"PaymentAuditLogged","paymentAttemptId":"%s","orderId":"%s","status":"%s"}"""
                    .formatted(command.paymentAttemptId(), command.orderId(), result.status());
        }
    }
}

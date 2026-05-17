# Kafka 核心机制 Java 模拟

这个目录是一个纯 Java 示例工程，用代码演示 Kafka 的几个核心机制：

- Controller 选举：controller epoch、broker fencing、元数据镜像、分区 leader 迁移
- 日志复制：leader/follower log、ISR、high watermark、acks=all 与 leader failover
- 事务协调：transactional.id、producer epoch fencing、事务状态机、commit/abort marker、read_committed 过滤
- 订单支付分布式事务：Kafka 事务 API、幂等生产者、跨 Topic/跨分区原子写入、消费 offset 与输出事件原子提交

运行：

```bash
cd kafka
mvn -q compile exec:java
```

这些代码是教学模拟器，重点是把 Kafka 关键概念落到可观察的数据结构和状态变化上，并不等价于 Kafka 的生产级源码实现。

## 订单支付分布式事务方案

新增示例：[OrderPaymentTransactionDemo.java](src/main/java/com/example/kafka/transaction/OrderPaymentTransactionDemo.java)

场景：支付服务消费 `payment.commands`，完成一次订单扣款后，需要同时写入：

- `payment.events`：支付成功/失败事件，key 为 `orderId`
- `order.events`：订单已支付事件，key 为 `orderId`
- `accounting.ledger`：账务流水，key 为 `merchantId`
- `risk.audit`：风控审计事件，key 为 `paymentAttemptId`
- `payment.commands` 的消费 offset：通过 `sendOffsetsToTransaction` 与输出事件一起提交

这些记录会落到不同 Topic 和不同分区。Kafka 事务保证：同一个 `transactional.id` 发出的所有数据记录和 offset 提交，要么全部对 `read_committed` 消费者可见，要么全部不可见。

### 核心流程

```text
payment.commands(read_committed, enable.auto.commit=false)
        |
        v
PaymentTransactionProcessor
        |
        |-- 1. 使用 paymentAttemptId 调用外部支付网关，网关必须支持幂等
        |-- 2. producer.beginTransaction()
        |-- 3. 写 payment.events / order.events / accounting.ledger / risk.audit
        |-- 4. producer.sendOffsetsToTransaction(...)
        |-- 5. producer.commitTransaction()
        |
        v
下游消费者全部使用 isolation.level=read_committed
```

订单支付伪代码：

```java
// 外部支付不属于 Kafka 事务，必须靠业务幂等补齐。
PaymentResult capture(PaymentCommand command) {
    // 1. 先以 paymentAttemptId 查询本地支付尝试表或支付网关
    // 2. 若已成功，直接返回之前的 providerTxnId
    // 3. 若未成功，调用支付网关 capture(amount, currency, idempotencyKey=paymentAttemptId)
    // 4. 将网关结果按 paymentAttemptId 幂等保存
    // 5. 返回 PaymentResult
}
```

### 关键 Kafka 配置

生产者：

- `transactional.id=payment-service-${instanceSlot}`：同一逻辑实例固定使用稳定 ID，重启后由 producer epoch fence 掉旧实例。
- `enable.idempotence=true`：启用幂等生产，避免重试造成分区内重复写入。
- `acks=all`、`retries=Integer.MAX_VALUE`、`max.in.flight.requests.per.connection<=5`：配合幂等生产者保证顺序与可靠性。
- `transaction.timeout.ms`：必须小于 broker 的 `transaction.max.timeout.ms`，支付网关调用不应放进长事务里。

消费者：

- `enable.auto.commit=false`：offset 只允许由事务提交。
- `isolation.level=read_committed`：只消费已提交事务的消息，并跳过 aborted 事务消息。
- `max.poll.records` 和处理超时要匹配，避免事务时间过长或 consumer rebalance。

Broker / Topic：

- 业务 Topic：`replication.factor>=3`，`min.insync.replicas>=2`，生产者使用 `acks=all`。
- 事务状态 Topic `__transaction_state`：生产环境保持足够副本和 ISR，事务协调器依赖它恢复事务状态。
- 给 `payment.commands`、`order.events`、`payment.events` 设计稳定 key，保证同一订单在需要顺序的 Topic 内进入同一分区。

### 事务协调器如何工作

1. Producer 调用 `initTransactions()`，事务协调器为 `transactional.id` 分配或恢复 `producerId`，并递增 `producerEpoch`。
2. 若旧实例继续使用旧 epoch 写入，会被 broker fence，避免双主写。
3. Producer 在事务内第一次写入某个分区时，会把分区加入事务元数据。
4. `commitTransaction()` 时，协调器向所有参与分区写入 COMMIT marker；`abortTransaction()` 写入 ABORT marker。
5. `read_committed` 消费者依据 marker、LSO（Last Stable Offset）过滤未完成或已回滚事务。

### 性能影响

- 多一次事务协调：`initTransactions`、`begin/commit/abort` 都会增加协调器交互和状态写入。
- 多分区 fan-out 越大，commit marker 越多，事务提交延迟越高。
- `read_committed` 消费者会受 LSO 约束，遇到未完成事务时可见进度可能落后于 high watermark。
- 小事务吞吐低，建议在 SLA 允许范围内用 `linger.ms`、`batch.size` 和每事务多条记录提升批量效率。
- 不要把慢速外部 RPC 包进 Kafka 事务。示例先完成幂等支付，再开启 Kafka 短事务发布结果。

### 故障处理

- 支付成功但 Kafka commit 失败：消息会被重新消费；支付网关以 `paymentAttemptId` 返回同一结果，再重新提交 Kafka 事务。
- Producer 进程卡死后重启：相同 `transactional.id` 的新实例递增 epoch，旧实例被 fence。
- 事务超时：协调器 abort，`read_committed` 下游不可见；业务依赖重试重新发布。
- Consumer rebalance：使用 `sendOffsetsToTransaction` 后，只有事务提交成功 offset 才前进；失败会由新 owner 重放。
- 下游重复消费：Kafka 事务提供读写链路 exactly-once，业务落库仍需用事件 ID 或业务主键幂等。

### 生产部署清单

- 每个运行实例分配稳定且唯一的 `transactional.id`，不要在横向扩容时复用同一个 ID。
- 监控 `transaction-start-rate`、`transaction-commit-rate`、`transaction-abort-rate`、commit latency、producer fencing、consumer lag。
- 事务超时要覆盖正常处理耗时，但不要用来包住长时间人工流程或慢支付通道。
- 所有参与链路的下游消费者统一使用 `read_committed`，否则会读到未提交数据。
- 对外部系统使用幂等键、状态表或 Saga 补偿；Kafka 事务不能原子提交数据库、HTTP 支付网关和 Kafka。

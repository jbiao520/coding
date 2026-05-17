# Redis 高可用架构 Java 模拟

这个目录是一个纯 Java 示例工程，用代码演示 Redis 常见高可用架构的核心机制：

- Redis Cluster：16384 槽位、key hash tag、master/replica、主节点故障后副本提升
- Redis Sentinel：哨兵监控、quorum 判定、故障转移、客户端继续写入新主库
- 客户端分片：一致性哈希、虚拟节点、primary/standby、主分片宕机后的备用分片提升

运行：

```bash
cd redis
mvn -q compile exec:java
```

这些代码是教学模拟器，重点是把 Redis 高可用架构里的路由、复制、选主和故障恢复过程落到可观察的数据结构和状态变化上，并不等价于 Redis/Jedis/Lettuce 的生产级实现。

## 目录

```text
src/main/java/com/example/redis
├── Main.java
├── cluster/RedisClusterDemo.java
├── common/RedisNode.java
├── common/SlotHash.java
├── sentinel/RedisSentinelDemo.java
└── sharding/ClientSideShardingDemo.java
```

## 关键点

Redis Cluster 适合服务端分片，客户端只需要理解 slot 到节点的映射，集群内部负责节点间槽位归属和故障转移。

Sentinel 不做分片，主要解决一个 master 多个 replica 场景下的自动选主和主从切换。

客户端分片把路由逻辑放在业务侧或代理侧，灵活但需要自己处理扩缩容、一致性哈希、故障转移和数据迁移。

# Raft Java 实现

这个目录是一个纯 Java Raft 教学实现，覆盖 Raft 的核心机制：

- follower / candidate / leader 三种角色状态机
- 随机 election timeout、RequestVote 选举、任期递增、投票限制
- AppendEntries 心跳与日志复制
- prevLogIndex / prevLogTerm 日志一致性检查与冲突回退
- majority commit、只提交当前任期日志的 leader 提交规则
- committed log 依次应用到 key-value 状态机
- 内存网络支持节点停止、恢复、隔离，用于观察 leader failover 和日志追赶

运行：

```bash
cd RAFT
mvn -q compile exec:java
```

示例命令格式：

```text
set <key> <value>
delete <key>
```

这是为了理解 Raft 算法而写的单进程实现，不包含生产系统需要的磁盘持久化、快照压缩、动态成员变更、真实 RPC、读线性化租约等工程能力。

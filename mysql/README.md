# MySQL 核心机制 Java 模拟

这个目录是一个纯 Java 示例工程，用代码演示这些 MySQL/InnoDB 知识点：

- B+树索引：页分裂、页合并、树高、点查/范围查 IO 次数估算
- 聚簇索引与二级索引：回表、覆盖索引、索引下推
- 事务隔离级别：读未提交、读已提交、可重复读、串行化
- MVCC：undo log 版本链、ReadView 生成规则
- 锁机制：行锁、间隙锁、Next-Key 锁、意向锁、自增锁
- redo log 与 undo log：WAL、崩溃恢复、回滚

运行：

```bash
cd mysql
mvn -q compile exec:java
```

这些代码是教学模拟器，重点是把概念落到可观察的数据结构和状态变化上，并不等价于 InnoDB 的生产级实现。

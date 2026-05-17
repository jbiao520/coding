package com.example.mysql.transaction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class IsolationLevelDemo {
    public static void run() {
        System.out.println("\n========== 事务隔离级别：读未提交、读已提交、可重复读、串行化 ==========");
        showDirtyRead();
        showNonRepeatableRead();
        showRepeatableReadSnapshot();
        showSerializableRangeProtection();
    }

    private static void showDirtyRead() {
        DemoTable table = new DemoTable();
        table.commitInsert(1, 100);

        Transaction writer = table.begin(IsolationLevel.READ_COMMITTED);
        Transaction reader = table.begin(IsolationLevel.READ_UNCOMMITTED);
        writer.update(1, 80);

        System.out.printf("读未提交：T2尚未提交余额80，T1读到=%d，可能发生脏读%n", reader.read(1));
        writer.rollback();
    }

    private static void showNonRepeatableRead() {
        DemoTable table = new DemoTable();
        table.commitInsert(1, 100);

        Transaction reader = table.begin(IsolationLevel.READ_COMMITTED);
        Transaction writer = table.begin(IsolationLevel.READ_COMMITTED);

        int first = reader.read(1);
        writer.update(1, 120);
        writer.commit();
        int second = reader.read(1);

        System.out.printf("读已提交：同一事务两次读取，第一次=%d，第二次=%d，可能不可重复读%n", first, second);
    }

    private static void showRepeatableReadSnapshot() {
        DemoTable table = new DemoTable();
        table.commitInsert(1, 100);

        Transaction reader = table.begin(IsolationLevel.REPEATABLE_READ);
        Transaction writer = table.begin(IsolationLevel.READ_COMMITTED);

        int first = reader.read(1);
        writer.update(1, 120);
        writer.commit();
        int second = reader.read(1);

        System.out.printf("可重复读：事务开始时生成快照，第一次=%d，第二次=%d%n", first, second);
    }

    private static void showSerializableRangeProtection() {
        DemoTable table = new DemoTable();
        table.commitInsert(1, 50);
        table.commitInsert(2, 150);

        Transaction reader = table.begin(IsolationLevel.SERIALIZABLE);
        int firstCount = reader.countGreaterOrEqual(100);

        Transaction writer = table.begin(IsolationLevel.READ_COMMITTED);
        boolean inserted = writer.insert(3, 180);
        if (!inserted) {
            writer.rollback();
        }

        int secondCount = reader.countGreaterOrEqual(100);
        reader.commit();

        System.out.printf("串行化：范围读加读锁，插入180被阻塞=%s，两次数量=%d/%d%n",
                !inserted, firstCount, secondCount);
    }

    enum IsolationLevel {
        READ_UNCOMMITTED,
        READ_COMMITTED,
        REPEATABLE_READ,
        SERIALIZABLE
    }

    private static class DemoTable {
        private final Map<Integer, Integer> committed = new LinkedHashMap<>();
        private final Map<Integer, Integer> uncommitted = new HashMap<>();
        private boolean serializableRangeReadLock;

        void commitInsert(int id, int amount) {
            committed.put(id, amount);
        }

        Transaction begin(IsolationLevel isolationLevel) {
            Map<Integer, Integer> snapshot = isolationLevel == IsolationLevel.REPEATABLE_READ
                    ? new LinkedHashMap<>(committed)
                    : Map.of();
            return new Transaction(this, isolationLevel, snapshot);
        }
    }

    private static class Transaction {
        private final DemoTable table;
        private final IsolationLevel isolationLevel;
        private final Map<Integer, Integer> snapshot;
        private final Map<Integer, Integer> localWrites = new LinkedHashMap<>();

        Transaction(DemoTable table, IsolationLevel isolationLevel, Map<Integer, Integer> snapshot) {
            this.table = table;
            this.isolationLevel = isolationLevel;
            this.snapshot = snapshot;
        }

        int read(int id) {
            if (localWrites.containsKey(id)) {
                return localWrites.get(id);
            }
            if (isolationLevel == IsolationLevel.READ_UNCOMMITTED && table.uncommitted.containsKey(id)) {
                return table.uncommitted.get(id);
            }
            if (isolationLevel == IsolationLevel.REPEATABLE_READ) {
                return snapshot.get(id);
            }
            return table.committed.get(id);
        }

        void update(int id, int amount) {
            localWrites.put(id, amount);
            table.uncommitted.put(id, amount);
        }

        boolean insert(int id, int amount) {
            if (table.serializableRangeReadLock && amount >= 100) {
                return false;
            }
            update(id, amount);
            return true;
        }

        int countGreaterOrEqual(int amount) {
            if (isolationLevel == IsolationLevel.SERIALIZABLE) {
                table.serializableRangeReadLock = true;
            }

            Map<Integer, Integer> source = isolationLevel == IsolationLevel.REPEATABLE_READ
                    ? snapshot
                    : table.committed;
            return (int) source.values().stream().filter(v -> v >= amount).count();
        }

        void commit() {
            table.committed.putAll(localWrites);
            for (Integer id : localWrites.keySet()) {
                table.uncommitted.remove(id);
            }
            table.serializableRangeReadLock = false;
        }

        void rollback() {
            for (Integer id : localWrites.keySet()) {
                table.uncommitted.remove(id);
            }
            table.serializableRangeReadLock = false;
        }
    }
}

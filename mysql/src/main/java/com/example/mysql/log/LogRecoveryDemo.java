package com.example.mysql.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LogRecoveryDemo {
    public static void run() {
        System.out.println("\n========== redo log与undo log：WAL、崩溃恢复、回滚 ==========");

        MiniEngine engine = new MiniEngine();
        engine.bootstrapPage(1, 100);

        int trx1 = engine.begin();
        engine.update(trx1, 1, 80);
        try {
            engine.flushPage(1);
        } catch (IllegalStateException ex) {
            System.out.println("WAL约束：" + ex.getMessage());
        }
        engine.commit(trx1);

        System.out.println("提交后脏页尚未刷盘，磁盘页=" + engine.diskValue(1) + "，内存页=" + engine.bufferValue(1));
        engine.crash();
        engine.recover();
        System.out.println("崩溃恢复：redo重放后磁盘页=" + engine.diskValue(1));

        int trx2 = engine.begin();
        engine.update(trx2, 1, 60);
        System.out.println("T2更新未提交，内存页=" + engine.bufferValue(1));
        engine.rollback(trx2);
        System.out.println("回滚：undo恢复旧值，内存页=" + engine.bufferValue(1));
    }

    private static class MiniEngine {
        private final Map<Integer, Page> disk = new HashMap<>();
        private final Map<Integer, Page> bufferPool = new HashMap<>();
        private final List<RedoRecord> redoLog = new ArrayList<>();
        private final ArrayDeque<UndoRecord> undoLog = new ArrayDeque<>();
        private final Set<Integer> activeTransactions = new HashSet<>();
        private final Set<Integer> committedTransactions = new HashSet<>();
        private int nextTrxId = 1;
        private int nextLsn = 1;
        private int flushedRedoLsn;

        void bootstrapPage(int pageId, int value) {
            disk.put(pageId, new Page(pageId, value, 0));
        }

        int begin() {
            int trxId = nextTrxId++;
            activeTransactions.add(trxId);
            return trxId;
        }

        void update(int trxId, int pageId, int newValue) {
            Page page = loadPage(pageId);
            int before = page.value();
            int lsn = nextLsn++;

            undoLog.push(new UndoRecord(trxId, pageId, before));
            redoLog.add(new RedoRecord(lsn, trxId, pageId, before, newValue));
            bufferPool.put(pageId, new Page(pageId, newValue, lsn));
            System.out.printf("T%d update page=%d: %d -> %d，生成undo(before=%d)和redo(lsn=%d)%n",
                    trxId, pageId, before, newValue, before, lsn);
        }

        void commit(int trxId) {
            flushedRedoLsn = redoLog.stream()
                    .mapToInt(RedoRecord::lsn)
                    .max()
                    .orElse(flushedRedoLsn);
            activeTransactions.remove(trxId);
            committedTransactions.add(trxId);
            System.out.printf("T%d commit：先刷redo到LSN=%d，再允许事务提交%n", trxId, flushedRedoLsn);
        }

        void rollback(int trxId) {
            List<UndoRecord> remaining = new ArrayList<>();
            while (!undoLog.isEmpty()) {
                UndoRecord undo = undoLog.pop();
                if (undo.trxId() == trxId) {
                    Page current = loadPage(undo.pageId());
                    bufferPool.put(undo.pageId(), new Page(undo.pageId(), undo.beforeValue(), current.lsn()));
                } else {
                    remaining.add(undo);
                }
            }
            for (int i = remaining.size() - 1; i >= 0; i--) {
                undoLog.push(remaining.get(i));
            }
            activeTransactions.remove(trxId);
        }

        void flushPage(int pageId) {
            Page page = loadPage(pageId);
            if (page.lsn() > flushedRedoLsn) {
                throw new IllegalStateException("脏页LSN=" + page.lsn()
                        + " 大于已刷redo LSN=" + flushedRedoLsn
                        + "，不能先刷数据页");
            }
            disk.put(pageId, page);
        }

        void crash() {
            bufferPool.clear();
            System.out.println("模拟崩溃：Buffer Pool丢失，redo/undo保留在持久化日志中");
        }

        void recover() {
            for (RedoRecord redo : redoLog) {
                if (redo.lsn() <= flushedRedoLsn && committedTransactions.contains(redo.trxId())) {
                    Page page = disk.get(redo.pageId());
                    if (page == null || page.lsn() < redo.lsn()) {
                        disk.put(redo.pageId(), new Page(redo.pageId(), redo.afterValue(), redo.lsn()));
                    }
                }
            }

            for (Integer trxId : List.copyOf(activeTransactions)) {
                rollback(trxId);
            }
        }

        int diskValue(int pageId) {
            return disk.get(pageId).value();
        }

        int bufferValue(int pageId) {
            return loadPage(pageId).value();
        }

        private Page loadPage(int pageId) {
            return bufferPool.computeIfAbsent(pageId, id -> disk.get(id));
        }
    }

    private record Page(int id, int value, int lsn) {
    }

    private record RedoRecord(int lsn, int trxId, int pageId, int beforeValue, int afterValue) {
    }

    private record UndoRecord(int trxId, int pageId, int beforeValue) {
    }
}

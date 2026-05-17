package com.example.mysql.lock;

import java.util.ArrayList;
import java.util.List;

public class LockManagerDemo {
    public static void run() {
        System.out.println("\n========== 锁机制：行锁、间隙锁、Next-Key锁、意向锁、自增锁 ==========");

        LockManager locks = new LockManager();

        System.out.println("T1加意向排他锁IX => " + locks.tryLock(new Lock(1, LockType.TABLE_IX, null, null, null)));
        System.out.println("T1加行锁 id=10 => " + locks.tryLock(new Lock(1, LockType.ROW_X, 10, null, null)));
        System.out.println("T2再加行锁 id=10 => " + locks.tryLock(new Lock(2, LockType.ROW_X, 10, null, null)) + "，被T1阻塞");
        System.out.println("T2加行锁 id=11 => " + locks.tryLock(new Lock(2, LockType.ROW_X, 11, null, null)) + "，不同行可并发");

        System.out.println("T3加间隙锁 (10,20) => " + locks.tryLock(new Lock(3, LockType.GAP_X, null, 10, 20)));
        System.out.println("T4插入 id=15 => " + locks.canInsert(4, 15) + "，落在间隙内被阻塞");
        System.out.println("T4插入 id=21 => " + locks.canInsert(4, 21) + "，不在间隙内可插入");

        System.out.println("T5加Next-Key锁 (20,30] => " + locks.tryLock(new Lock(5, LockType.NEXT_KEY_X, 30, 20, 30)));
        System.out.println("T6插入 id=25 => " + locks.canInsert(6, 25) + "，被Next-Key的间隙部分阻塞");
        System.out.println("T6修改 id=30 => " + locks.tryLock(new Lock(6, LockType.ROW_X, 30, null, null)) + "，被Next-Key的记录部分阻塞");

        System.out.println("T7加意向共享锁IS => " + locks.tryLock(new Lock(7, LockType.TABLE_IS, null, null, null)));
        System.out.println("意向锁之间兼容，用来快速判断表锁与行锁是否冲突");

        System.out.println("T8加AUTO_INC锁 => " + locks.tryLock(new Lock(8, LockType.AUTO_INC, null, null, null)));
        System.out.println("T9加AUTO_INC锁 => " + locks.tryLock(new Lock(9, LockType.AUTO_INC, null, null, null)) + "，自增分配阶段互斥");
    }

    private static class LockManager {
        private final List<Lock> granted = new ArrayList<>();

        boolean tryLock(Lock requested) {
            for (Lock existing : granted) {
                if (existing.trxId() == requested.trxId()) {
                    continue;
                }
                if (!compatible(existing, requested)) {
                    return false;
                }
            }
            granted.add(requested);
            return true;
        }

        boolean canInsert(int trxId, int key) {
            for (Lock existing : granted) {
                if (existing.trxId() == trxId) {
                    continue;
                }
                if (existing.type() == LockType.GAP_X && insideOpenRange(key, existing.from(), existing.to())) {
                    return false;
                }
                if (existing.type() == LockType.NEXT_KEY_X && insideOpenRange(key, existing.from(), existing.to())) {
                    return false;
                }
            }
            return true;
        }

        private boolean compatible(Lock a, Lock b) {
            if (a.type().isIntention() && b.type().isIntention()) {
                return true;
            }
            if (a.type() == LockType.AUTO_INC && b.type() == LockType.AUTO_INC) {
                return false;
            }
            if (a.type() == LockType.AUTO_INC || b.type() == LockType.AUTO_INC) {
                return true;
            }
            if (a.type() == LockType.ROW_X && b.type() == LockType.ROW_X) {
                return !a.recordKey().equals(b.recordKey());
            }
            if (a.type() == LockType.NEXT_KEY_X && b.type() == LockType.ROW_X) {
                return !a.recordKey().equals(b.recordKey());
            }
            if (a.type() == LockType.ROW_X && b.type() == LockType.NEXT_KEY_X) {
                return !a.recordKey().equals(b.recordKey());
            }
            if (a.type().isRange() && b.type().isRange()) {
                return !rangeOverlap(a.from(), a.to(), b.from(), b.to());
            }
            return true;
        }

        private boolean insideOpenRange(int key, Integer from, Integer to) {
            return key > from && key < to;
        }

        private boolean rangeOverlap(Integer aFrom, Integer aTo, Integer bFrom, Integer bTo) {
            return aFrom < bTo && bFrom < aTo;
        }
    }

    private record Lock(int trxId, LockType type, Integer recordKey, Integer from, Integer to) {
    }

    private enum LockType {
        TABLE_IS,
        TABLE_IX,
        ROW_X,
        GAP_X,
        NEXT_KEY_X,
        AUTO_INC;

        boolean isIntention() {
            return this == TABLE_IS || this == TABLE_IX;
        }

        boolean isRange() {
            return this == GAP_X || this == NEXT_KEY_X;
        }
    }
}

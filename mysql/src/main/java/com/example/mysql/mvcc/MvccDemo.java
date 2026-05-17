package com.example.mysql.mvcc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public class MvccDemo {
    public static void run() {
        System.out.println("\n========== MVCC实现：undo log版本链、ReadView生成规则 ==========");

        TransactionManager txManager = new TransactionManager();
        MvccTable table = new MvccTable();
        table.bootstrap(1, "v0", 1);

        Transaction trx10 = txManager.begin();
        Transaction trx11 = txManager.begin();
        table.update(1, "v10-uncommitted", trx10.id());

        ReadView readViewInTrx11 = txManager.createReadView(trx11.id());
        System.out.println("T11 ReadView => " + readViewInTrx11);
        System.out.println("T11读取：跳过T10未提交版本 => " + table.read(1, readViewInTrx11).orElse("不可见"));

        txManager.commit(trx10);
        Transaction trx12 = txManager.begin();
        ReadView readViewInTrx12 = txManager.createReadView(trx12.id());
        System.out.println("T10提交后，T12新ReadView => " + readViewInTrx12);
        System.out.println("T12读取：能看到T10已提交版本 => " + table.read(1, readViewInTrx12).orElse("不可见"));

        table.update(1, "v12-own-update", trx12.id());
        System.out.println("T12读取自己的新版本 => " + table.read(1, readViewInTrx12).orElse("不可见"));
        table.printVersionChain(1);
    }

    private static class TransactionManager {
        private int nextTrxId = 10;
        private final Set<Integer> activeTrxIds = new TreeSet<>();

        Transaction begin() {
            int id = nextTrxId++;
            activeTrxIds.add(id);
            return new Transaction(id);
        }

        void commit(Transaction transaction) {
            activeTrxIds.remove(transaction.id());
        }

        ReadView createReadView(int creatorTrxId) {
            Set<Integer> active = new HashSet<>(activeTrxIds);
            int lowLimitId = nextTrxId;
            int upLimitId = active.stream().min(Integer::compareTo).orElse(lowLimitId);
            return new ReadView(creatorTrxId, active, upLimitId, lowLimitId);
        }
    }

    private static class MvccTable {
        private final Map<Integer, RowVersion> rows = new LinkedHashMap<>();

        void bootstrap(int id, String value, int trxId) {
            rows.put(id, new RowVersion(id, value, trxId, false, null));
        }

        void update(int id, String newValue, int trxId) {
            RowVersion current = rows.get(id);
            RowVersion newHead = new RowVersion(id, newValue, trxId, false, current);
            rows.put(id, newHead);
        }

        Optional<String> read(int id, ReadView readView) {
            RowVersion cursor = rows.get(id);
            while (cursor != null) {
                if (readView.isVisible(cursor.trxId())) {
                    return cursor.deleted() ? Optional.empty() : Optional.of(cursor.value());
                }
                cursor = cursor.previous();
            }
            return Optional.empty();
        }

        void printVersionChain(int id) {
            List<String> versions = new ArrayList<>();
            RowVersion cursor = rows.get(id);
            while (cursor != null) {
                versions.add(String.format("[value=%s,trx=%d,roll_ptr=%s]",
                        cursor.value(),
                        cursor.trxId(),
                        cursor.previous() == null ? "null" : "prev"));
                cursor = cursor.previous();
            }
            System.out.println("undo log版本链 head -> " + String.join(" -> ", versions));
        }
    }

    private record Transaction(int id) {
    }

    private record RowVersion(int id, String value, int trxId, boolean deleted, RowVersion previous) {
    }

    private record ReadView(int creatorTrxId, Set<Integer> activeTrxIds, int upLimitId, int lowLimitId) {
        boolean isVisible(int rowTrxId) {
            if (rowTrxId == creatorTrxId) {
                return true;
            }
            if (rowTrxId < upLimitId) {
                return true;
            }
            if (rowTrxId >= lowLimitId) {
                return false;
            }
            return !activeTrxIds.contains(rowTrxId);
        }

        @Override
        public String toString() {
            return "creator=" + creatorTrxId
                    + ", active=" + activeTrxIds
                    + ", upLimitId(min active)=" + upLimitId
                    + ", lowLimitId(next trx)=" + lowLimitId;
        }
    }
}

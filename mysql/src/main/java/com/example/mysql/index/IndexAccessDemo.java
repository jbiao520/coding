package com.example.mysql.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

public class IndexAccessDemo {
    public static void run() {
        System.out.println("\n========== 聚簇索引与二级索引：回表、覆盖索引、索引下推 ==========");

        UserTable table = new UserTable();
        table.insert(new UserRow(1, "Alice", 18, "ACTIVE", "杭州"));
        table.insert(new UserRow(2, "Bob", 25, "ACTIVE", "上海"));
        table.insert(new UserRow(3, "Alice", 32, "LOCKED", "北京"));
        table.insert(new UserRow(4, "Alice", 28, "ACTIVE", "深圳"));

        table.findByPrimaryKey(3);
        table.findBySecondaryIndexNeedBackToTable("Alice", row -> row.age() >= 20);
        table.findByCoveringSecondaryIndex("Alice");
        table.findBySecondaryIndexWithIndexConditionPushdown("Alice", entry -> entry.age() >= 20);
    }

    private static class UserTable {
        private final TreeMap<Integer, UserRow> clusteredIndex = new TreeMap<>();
        private final TreeMap<String, List<SecondaryEntry>> secondaryIndexByName = new TreeMap<>();

        void insert(UserRow row) {
            clusteredIndex.put(row.id(), row);
            secondaryIndexByName
                    .computeIfAbsent(row.name(), ignored -> new ArrayList<>())
                    .add(new SecondaryEntry(row.name(), row.age(), row.status(), row.id()));
            secondaryIndexByName.get(row.name()).sort(Comparator.comparingInt(SecondaryEntry::primaryKey));
        }

        void findByPrimaryKey(int id) {
            UserRow row = clusteredIndex.get(id);
            System.out.printf("聚簇索引点查 id=%d：B+树叶子页直接存整行 => %s，IO约为树高%n", id, row);
        }

        void findBySecondaryIndexNeedBackToTable(String name, Predicate<UserRow> whereAfterBackToTable) {
            List<SecondaryEntry> entries = secondaryIndexByName.getOrDefault(name, List.of());
            int secondaryIndexReads = 1;
            int backToTableReads = 0;
            List<UserRow> result = new ArrayList<>();

            for (SecondaryEntry entry : entries) {
                backToTableReads++;
                UserRow fullRow = clusteredIndex.get(entry.primaryKey());
                if (whereAfterBackToTable.test(fullRow)) {
                    result.add(fullRow);
                }
            }

            System.out.printf("二级索引 name=%s 后再按整行过滤：二级索引命中%d条，回表%d次，结果=%s，估算IO=%d%n",
                    name, entries.size(), backToTableReads, result, secondaryIndexReads + backToTableReads);
        }

        void findByCoveringSecondaryIndex(String name) {
            List<SecondaryEntry> entries = secondaryIndexByName.getOrDefault(name, List.of());
            List<Map<String, Object>> projection = entries.stream()
                    .map(entry -> Map.<String, Object>of(
                            "name", entry.name(),
                            "age", entry.age(),
                            "status", entry.status(),
                            "id", entry.primaryKey()))
                    .toList();

            System.out.printf("覆盖索引查询 select name,age,status,id where name=%s：二级索引已经包含全部列，无需回表，结果=%s%n",
                    name, projection);
        }

        void findBySecondaryIndexWithIndexConditionPushdown(String name, Predicate<SecondaryEntry> indexPredicate) {
            List<SecondaryEntry> entries = secondaryIndexByName.getOrDefault(name, List.of());
            int backToTableReads = 0;
            List<UserRow> result = new ArrayList<>();

            for (SecondaryEntry entry : entries) {
                if (!indexPredicate.test(entry)) {
                    continue;
                }
                backToTableReads++;
                result.add(clusteredIndex.get(entry.primaryKey()));
            }

            System.out.printf("索引下推 name=%s and age>=20：先在二级索引叶子页过滤，回表%d次，结果=%s%n",
                    name, backToTableReads, result);
        }
    }

    private record UserRow(int id, String name, int age, String status, String city) {
    }

    private record SecondaryEntry(String name, int age, String status, int primaryKey) {
    }
}

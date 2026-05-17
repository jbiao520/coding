package com.example.mysql;

import com.example.mysql.btree.BPlusTreeIndexDemo;
import com.example.mysql.index.IndexAccessDemo;
import com.example.mysql.lock.LockManagerDemo;
import com.example.mysql.log.LogRecoveryDemo;
import com.example.mysql.mvcc.MvccDemo;
import com.example.mysql.transaction.IsolationLevelDemo;

public class Main {
    public static void main(String[] args) {
        BPlusTreeIndexDemo.run();
        IndexAccessDemo.run();
        IsolationLevelDemo.run();
        MvccDemo.run();
        LockManagerDemo.run();
        LogRecoveryDemo.run();
    }
}

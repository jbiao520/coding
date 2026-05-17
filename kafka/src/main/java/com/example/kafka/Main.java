package com.example.kafka;

import com.example.kafka.controller.ControllerElectionDemo;
import com.example.kafka.replication.LogReplicationDemo;
import com.example.kafka.transaction.OrderPaymentTransactionDemo;
import com.example.kafka.transaction.TransactionCoordinatorDemo;

public class Main {
    public static void main(String[] args) {
        ControllerElectionDemo.run();
        LogReplicationDemo.run();
        TransactionCoordinatorDemo.run();
        OrderPaymentTransactionDemo.run();
    }
}

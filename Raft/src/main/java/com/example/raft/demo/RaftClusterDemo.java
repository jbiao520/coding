package com.example.raft.demo;

import com.example.raft.core.ClientResponse;
import com.example.raft.core.RaftNode;
import com.example.raft.transport.InMemoryRaftCluster;

public class RaftClusterDemo {
    public static void run() {
        InMemoryRaftCluster cluster = InMemoryRaftCluster.of("n1", "n2", "n3", "n4", "n5");

        System.out.println("== 启动 5 节点 Raft 集群并等待选主 ==");
        RaftNode firstLeader = cluster.runUntilLeaderElected(50)
                .orElseThrow(() -> new IllegalStateException("leader was not elected"));
        cluster.runTicks(3);
        System.out.println("leader: " + firstLeader.id());
        System.out.println(cluster.describe());

        System.out.println();
        System.out.println("== 向 leader 提交两条 key-value 命令 ==");
        print(cluster.propose("set color blue"));
        print(cluster.propose("set city shanghai"));
        cluster.runTicks(3);
        System.out.println(cluster.describe());

        System.out.println();
        System.out.println("== 停掉当前 leader，剩余节点重新选主 ==");
        cluster.stop(firstLeader.id());
        cluster.runUntilLeaderElected(80)
                .orElseThrow(() -> new IllegalStateException("new leader was not elected"));
        cluster.runTicks(3);
        System.out.println("new leader: " + cluster.leaderId());
        print(cluster.propose("set color green"));
        cluster.runTicks(3);
        System.out.println(cluster.describe());

        System.out.println();
        System.out.println("== 原 leader 恢复，日志追赶到新 leader ==");
        cluster.start(firstLeader.id());
        cluster.runTicks(10);
        System.out.println(cluster.describe());
    }

    private static void print(ClientResponse response) {
        System.out.printf(
                "client response: success=%s leader=%s term=%d index=%d message=%s%n",
                response.success(),
                response.leaderId(),
                response.term(),
                response.logIndex(),
                response.message()
        );
    }
}

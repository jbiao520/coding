package com.example.redis;

import com.example.redis.cluster.RedisClusterDemo;
import com.example.redis.sentinel.RedisSentinelDemo;
import com.example.redis.sharding.ClientSideShardingDemo;

public class Main {
    public static void main(String[] args) {
        RedisClusterDemo.run();
        RedisSentinelDemo.run();
        ClientSideShardingDemo.run();
    }
}

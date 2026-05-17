package com.example.redis.common;

import java.nio.charset.StandardCharsets;

public final class SlotHash {
    public static final int CLUSTER_SLOTS = 16_384;

    private SlotHash() {
    }

    public static int clusterSlot(String key) {
        String hashKey = hashTag(key);
        return crc16(hashKey.getBytes(StandardCharsets.UTF_8)) % CLUSTER_SLOTS;
    }

    public static int stableHash(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte b : bytes) {
            hash ^= Byte.toUnsignedInt(b);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    private static String hashTag(String key) {
        int start = key.indexOf('{');
        if (start < 0) {
            return key;
        }
        int end = key.indexOf('}', start + 1);
        if (end <= start + 1) {
            return key;
        }
        return key.substring(start + 1, end);
    }

    private static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= Byte.toUnsignedInt(b) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xffff;
            }
        }
        return crc;
    }
}

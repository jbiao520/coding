package com.example.redis.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class RedisNode {
    private final String id;
    private final Map<String, String> data = new LinkedHashMap<>();
    private Role role;
    private boolean alive = true;
    private RedisNode replicatingFrom;

    public RedisNode(String id, Role role) {
        this.id = id;
        this.role = role;
    }

    public String id() {
        return id;
    }

    public Role role() {
        return role;
    }

    public boolean isMaster() {
        return role == Role.MASTER;
    }

    public boolean isAlive() {
        return alive;
    }

    public void fail() {
        alive = false;
    }

    public void recover() {
        alive = true;
    }

    public Optional<RedisNode> replicatingFrom() {
        return Optional.ofNullable(replicatingFrom);
    }

    public void replicateFrom(RedisNode master) {
        role = Role.REPLICA;
        replicatingFrom = master;
        data.clear();
        data.putAll(master.data);
    }

    public void promoteToMaster() {
        role = Role.MASTER;
        replicatingFrom = null;
    }

    public void write(String key, String value) {
        ensureAlive();
        if (!isMaster()) {
            throw new IllegalStateException(id + " is replica, write must go to master");
        }
        data.put(key, value);
    }

    public Optional<String> read(String key) {
        ensureAlive();
        return Optional.ofNullable(data.get(key));
    }

    public void syncFromMaster() {
        if (replicatingFrom == null) {
            return;
        }
        ensureAlive();
        if (!replicatingFrom.isAlive()) {
            throw new IllegalStateException(id + " cannot sync from down master " + replicatingFrom.id);
        }
        data.clear();
        data.putAll(replicatingFrom.data);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(data);
    }

    private void ensureAlive() {
        if (!alive) {
            throw new IllegalStateException(id + " is down");
        }
    }

    @Override
    public String toString() {
        String upstream = replicatingFrom == null ? "" : ", from=" + replicatingFrom.id;
        return id + "(" + role + ", alive=" + alive + upstream + ", keys=" + data.keySet() + ")";
    }

    public enum Role {
        MASTER,
        REPLICA
    }
}

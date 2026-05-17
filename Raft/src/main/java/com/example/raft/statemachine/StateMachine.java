package com.example.raft.statemachine;

import java.util.Map;

public interface StateMachine {
    void apply(String command);

    Map<String, String> snapshot();
}

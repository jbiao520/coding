package com.example.codeobserver.terminal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "code-observer.terminal")
public record TerminalProperties(
        boolean enabled,
        String shell,
        int idleTimeoutMinutes,
        int maxSessions
) {
    public TerminalProperties {
        if (shell == null || shell.isBlank()) {
            shell = "/bin/zsh";
        }
        if (idleTimeoutMinutes <= 0) {
            idleTimeoutMinutes = 120;
        }
        if (maxSessions <= 0) {
            maxSessions = 8;
        }
    }

    public Duration idleTimeout() {
        return Duration.ofMinutes(idleTimeoutMinutes);
    }
}

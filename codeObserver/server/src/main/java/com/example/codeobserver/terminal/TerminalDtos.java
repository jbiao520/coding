package com.example.codeobserver.terminal;

import java.time.Instant;
import java.util.List;

public final class TerminalDtos {
    private TerminalDtos() {
    }

    public record CreateTerminalSessionRequest(
            String projectId,
            String root,
            String cwd,
            int cols,
            int rows
    ) {
    }

    public record ResizeTerminalSessionRequest(int cols, int rows) {
    }

    public record TerminalSessionResponse(
            String id,
            String title,
            String cwd,
            String projectId,
            String shell,
            Instant createdAt,
            boolean alive
    ) {
    }

    public record TerminalSessionListResponse(List<TerminalSessionResponse> sessions) {
    }
}

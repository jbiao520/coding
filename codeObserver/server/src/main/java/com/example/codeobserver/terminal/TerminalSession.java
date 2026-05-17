package com.example.codeobserver.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static com.example.codeobserver.terminal.TerminalDtos.TerminalSessionResponse;

public class TerminalSession {
    private final String id;
    private final String title;
    private final String projectId;
    private final Path root;
    private final Path cwd;
    private final String shell;
    private final Instant createdAt;
    private final PtyProcess process;
    private final OutputStream input;
    private final Set<WebSocketSession> sockets = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Instant lastTouched = Instant.now();

    public TerminalSession(
            String id,
            String title,
            String projectId,
            Path root,
            Path cwd,
            String shell,
            PtyProcess process,
            ObjectMapper objectMapper,
            ExecutorService outputExecutor,
            Runnable onExit
    ) {
        this.id = id;
        this.title = title;
        this.projectId = projectId;
        this.root = root;
        this.cwd = cwd;
        this.shell = shell;
        this.process = process;
        this.input = process.getOutputStream();
        this.objectMapper = objectMapper;
        this.createdAt = Instant.now();
        outputExecutor.submit(() -> pumpOutput(onExit));
    }

    public String id() {
        return id;
    }

    public Path root() {
        return root;
    }

    public Instant lastTouched() {
        return lastTouched;
    }

    public boolean isAlive() {
        return !closed.get() && process.isRunning();
    }

    public TerminalSessionResponse toResponse() {
        return new TerminalSessionResponse(id, title, cwd.toString(), projectId, shell, createdAt, isAlive());
    }

    public void attach(WebSocketSession socket) {
        touch();
        sockets.add(socket);
    }

    public void detach(WebSocketSession socket) {
        sockets.remove(socket);
    }

    public void write(String value) throws IOException {
        if (!isAlive()) {
            return;
        }
        touch();
        synchronized (input) {
            input.write(value.getBytes(StandardCharsets.UTF_8));
            input.flush();
        }
    }

    public void resize(int cols, int rows) {
        if (!isAlive()) {
            return;
        }
        touch();
        process.setWinSize(new WinSize(safeCols(cols), safeRows(rows)));
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sockets.forEach(socket -> {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing all sockets is best-effort during terminal cleanup.
            }
        });
        sockets.clear();
        process.destroy();
        try {
            if (process.isRunning()) {
                process.destroyForcibly();
            }
        } catch (UnsupportedOperationException ignored) {
            // Some PTY implementations do not support destroyForcibly.
        }
    }

    private void pumpOutput(Runnable onExit) {
        byte[] buffer = new byte[8192];
        try (InputStream output = process.getInputStream()) {
            int read;
            while ((read = output.read(buffer)) >= 0) {
                if (read > 0) {
                    broadcast("output", new String(buffer, 0, read, StandardCharsets.UTF_8), null);
                }
            }
        } catch (IOException ex) {
            if (!closed.get()) {
                broadcast("output", "\r\n[terminal disconnected: " + ex.getMessage() + "]\r\n", null);
            }
        } finally {
            closed.set(true);
            Integer exitCode = null;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // Process is being destroyed; the client only needs the exit signal.
            }
            broadcast("exit", null, exitCode);
            onExit.run();
        }
    }

    private void broadcast(String type, String data, Integer exitCode) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new TerminalSocketMessage(type, data, exitCode, null, null));
        } catch (IOException ex) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        sockets.removeIf(socket -> !socket.isOpen());
        sockets.forEach(socket -> {
            try {
                synchronized (socket) {
                    if (socket.isOpen()) {
                        socket.sendMessage(message);
                    }
                }
            } catch (IOException ex) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Ignore secondary close failures.
                }
            }
        });
    }

    private void touch() {
        lastTouched = Instant.now();
    }

    private int safeCols(int cols) {
        return Math.max(20, Math.min(400, cols));
    }

    private int safeRows(int rows) {
        return Math.max(6, Math.min(120, rows));
    }

    record TerminalSocketMessage(String type, String data, Integer exitCode, Integer cols, Integer rows) {
    }
}

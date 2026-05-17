package com.example.codeobserver.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import static com.example.codeobserver.terminal.TerminalDtos.CreateTerminalSessionRequest;
import static com.example.codeobserver.terminal.TerminalDtos.TerminalSessionResponse;

@Service
public class TerminalSessionManager {
    private final TerminalProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService outputExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "terminal-output");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "terminal-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public TerminalSessionManager(TerminalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        cleanupExecutor.scheduleWithFixedDelay(this::closeIdleSessions, 1, 1, TimeUnit.MINUTES);
    }

    public TerminalSession create(CreateTerminalSessionRequest request) {
        ensureEnabled();
        if (request == null) {
            throw new IllegalArgumentException("Terminal session request is required.");
        }
        if (sessions.size() >= properties.maxSessions()) {
            throw new IllegalStateException("Terminal session limit reached.");
        }
        Path root = normalizeDirectory(request.root(), "Workspace root");
        Path cwd = normalizeDirectory(request.cwd(), "Working directory");
        if (!cwd.startsWith(root)) {
            throw new IllegalArgumentException("Working directory must be inside the workspace root.");
        }

        int cols = clamp(request.cols(), 20, 400, 120);
        int rows = clamp(request.rows(), 6, 120, 32);
        String id = "term-" + UUID.randomUUID();
        String shell = properties.shell();
        String title = shellTitle(shell);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        env.put("PWD", cwd.toString());

        try {
            PtyProcess process = new PtyProcessBuilder(new String[]{shell, "-l"})
                    .setDirectory(cwd.toString())
                    .setEnvironment(env)
                    .setInitialColumns(cols)
                    .setInitialRows(rows)
                    .setRedirectErrorStream(true)
                    .start();
            TerminalSession session = new TerminalSession(
                    id,
                    title,
                    request.projectId() == null ? "" : request.projectId(),
                    root,
                    cwd,
                    shell,
                    process,
                    objectMapper,
                    outputExecutor,
                    () -> sessions.remove(id)
            );
            sessions.put(id, session);
            return session;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start terminal: " + ex.getMessage(), ex);
        }
    }

    public List<TerminalSessionResponse> list(String rootValue) {
        Path root = rootValue == null || rootValue.isBlank() ? null : Path.of(rootValue).toAbsolutePath().normalize();
        return sessions.values().stream()
                .filter(TerminalSession::isAlive)
                .filter(session -> root == null || session.root().equals(root))
                .map(TerminalSession::toResponse)
                .sorted(Comparator.comparing(TerminalSessionResponse::createdAt))
                .toList();
    }

    public Optional<TerminalSession> get(String id) {
        TerminalSession session = sessions.get(id);
        if (session == null || !session.isAlive()) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void resize(String id, int cols, int rows) {
        TerminalSession session = get(id).orElseThrow(() -> new IllegalArgumentException("Terminal session not found."));
        session.resize(cols, rows);
    }

    public void close(String id) {
        TerminalSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(TerminalSession::close);
        sessions.clear();
        cleanupExecutor.shutdownNow();
        outputExecutor.shutdownNow();
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Terminal support is disabled.");
        }
    }

    private void closeIdleSessions() {
        Instant cutoff = Instant.now().minus(properties.idleTimeout());
        sessions.values().stream()
                .filter(session -> session.lastTouched().isBefore(cutoff))
                .map(TerminalSession::id)
                .toList()
                .forEach(this::close);
    }

    private Path normalizeDirectory(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(label + " is not a directory.");
        }
        return path;
    }

    private String shellTitle(String shell) {
        Path shellPath = Path.of(shell);
        Path fileName = shellPath.getFileName();
        return fileName == null ? shell : fileName.toString();
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}

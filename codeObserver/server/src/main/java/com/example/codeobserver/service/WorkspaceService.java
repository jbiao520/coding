package com.example.codeobserver.service;

import static com.example.codeobserver.model.WorkspaceModels.ProjectDetail;
import static com.example.codeobserver.model.WorkspaceModels.ProjectSummary;
import static com.example.codeobserver.model.WorkspaceModels.OpenInIdeRequest;
import static com.example.codeobserver.model.WorkspaceModels.OpenInIdeResponse;
import static com.example.codeobserver.model.WorkspaceModels.SourceResponse;
import static com.example.codeobserver.model.WorkspaceModels.WorkspaceSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final JavaProjectAnalyzer analyzer;
    private final int maxDepth;
    private final Map<String, ProjectDetail> detailsById = new LinkedHashMap<>();
    private Path lastRoot;

    public WorkspaceService(JavaProjectAnalyzer analyzer, @Value("${observer.max-depth:7}") int maxDepth) {
        this.analyzer = analyzer;
        this.maxDepth = maxDepth;
    }

    public synchronized WorkspaceSnapshot scan(String rootValue) {
        Path root = normalizeRoot(rootValue);
        detailsById.clear();
        lastRoot = root;
        List<Path> projectRoots = findProjectRoots(root);
        for (Path projectRoot : projectRoots) {
            String id = stableId(projectRoot);
            detailsById.put(id, analyzer.analyze(projectRoot, id));
        }
        List<ProjectSummary> summaries = detailsById.values().stream()
                .map(ProjectDetail::summary)
                .sorted(Comparator.comparing(ProjectSummary::name))
                .toList();
        return new WorkspaceSnapshot(root.toString(), summaries);
    }

    public synchronized Optional<ProjectDetail> project(String id, String rootValue) {
        if (!detailsById.containsKey(id)) {
            scan(rootValue);
        }
        return Optional.ofNullable(detailsById.get(id));
    }

    public SourceResponse source(String filePath) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        try {
            return new SourceResponse(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read source file: " + path, ex);
        }
    }

    public synchronized OpenInIdeResponse openInIde(OpenInIdeRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return new OpenInIdeResponse(false, "Missing source path.", "");
        }
        if (lastRoot == null) {
            return new OpenInIdeResponse(false, "Scan a workspace before opening files in the IDE.", "");
        }

        Path root = lastRoot.toAbsolutePath().normalize();
        Path path = Path.of(request.path()).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return new OpenInIdeResponse(false, "Refusing to open a file outside the scanned workspace root.", "");
        }
        if (!Files.isRegularFile(path)) {
            return new OpenInIdeResponse(false, "Source path is not a regular file.", "");
        }

        int line = Math.max(1, request.line());
        String configured = System.getenv("OBSERVER_IDE_COMMAND");
        if (configured != null && !configured.isBlank()) {
            String command = configured
                    .replace("{line}", String.valueOf(line))
                    .replace("{file}", shellQuote(path.toString()));
            return runOpenCommand(command);
        }

        OpenInIdeResponse idea = runOpenCommand("idea --line " + line + " " + shellQuote(path.toString()));
        if (idea.ok()) {
            return idea;
        }
        return runOpenCommand("open -a " + shellQuote("IntelliJ IDEA") + " " + shellQuote(path.toString()));
    }

    private OpenInIdeResponse runOpenCommand(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-lc", command).start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished || process.exitValue() == 0) {
                return new OpenInIdeResponse(true, "Open command started.", command);
            }
            return new OpenInIdeResponse(false, "Open command exited with status " + process.exitValue() + ".", command);
        } catch (IOException ex) {
            return new OpenInIdeResponse(false, ex.getMessage(), command);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new OpenInIdeResponse(false, "Interrupted while opening the IDE.", command);
        }
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Path normalizeRoot(String rootValue) {
        if (rootValue != null && !rootValue.isBlank()) {
            return Path.of(rootValue).toAbsolutePath().normalize();
        }
        String configured = System.getenv("OBSERVER_ROOT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path parent = cwd.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("codeObserver")) {
            Path workspace = parent.getParent();
            if (workspace != null) {
                return workspace;
            }
        }
        return cwd;
    }

    private List<Path> findProjectRoots(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        if (looksLikeJavaProject(root)) {
            return List.of(root);
        }
        try (var stream = Files.walk(root, maxDepth)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(this::notIgnoredPath)
                    .filter(this::looksLikeJavaProject)
                    .filter(path -> !path.equals(root))
                    .filter(path -> !hasProjectAncestor(root, path))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private boolean looksLikeJavaProject(Path path) {
        if (Files.isRegularFile(path.resolve("pom.xml"))) {
            return true;
        }
        if (Files.isDirectory(path.resolve("src/main/java"))) {
            return true;
        }
        try (var stream = Files.list(path)) {
            return stream.anyMatch(child -> Files.isRegularFile(child) && child.toString().endsWith(".java"));
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean notIgnoredPath(Path path) {
        Set<String> ignored = Set.of("target", "build", "node_modules", ".git", ".idea", ".gradle", "dist");
        for (Path segment : path) {
            if (ignored.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasProjectAncestor(Path root, Path path) {
        Path cursor = path.getParent();
        while (cursor != null && !cursor.equals(root.getParent())) {
            if (!cursor.equals(path) && looksLikeJavaProject(cursor)) {
                return true;
            }
            if (cursor.equals(root)) {
                return false;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private String stableId(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return path.getFileName().toString() + "-" + HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException ex) {
            return path.getFileName().toString();
        }
    }
}

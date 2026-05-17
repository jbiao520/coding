package com.example.codeobserver.api;

import static com.example.codeobserver.model.WorkspaceModels.ProjectDetail;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiFlowRecommendationRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiFlowRecommendationResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiSummaryRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiSummaryResponse;
import static com.example.codeobserver.model.WorkspaceModels.OpenInIdeRequest;
import static com.example.codeobserver.model.WorkspaceModels.OpenInIdeResponse;
import static com.example.codeobserver.model.WorkspaceModels.SourceResponse;
import static com.example.codeobserver.model.WorkspaceModels.WorkspaceSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.codeobserver.service.AiSummaryService;
import com.example.codeobserver.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
@CrossOrigin(
        originPatterns = {"http://localhost:*", "http://127.0.0.1:*"},
        exposedHeaders = {"X-AI-Model"}
)
public class WorkspaceController {
    private final WorkspaceService workspaceService;
    private final AiSummaryService aiSummaryService;

    public WorkspaceController(WorkspaceService workspaceService, AiSummaryService aiSummaryService) {
        this.workspaceService = workspaceService;
        this.aiSummaryService = aiSummaryService;
    }

    @GetMapping("/workspace")
    WorkspaceSnapshot workspace(@RequestParam(required = false) String root) {
        return workspaceService.scan(root);
    }

    @GetMapping("/projects/{id}")
    ResponseEntity<?> project(
            @PathVariable String id,
            @RequestParam(required = false) String root
    ) {
        return workspaceService.project(id, root)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Project not found: " + id)));
    }

    @GetMapping("/source")
    SourceResponse source(@RequestParam String path) {
        return workspaceService.source(path);
    }

    @PostMapping("/source/open-in-ide")
    OpenInIdeResponse openInIde(@RequestBody OpenInIdeRequest request) {
        return workspaceService.openInIde(request);
    }

    @PostMapping("/ai/summary")
    ResponseEntity<?> aiSummary(@RequestBody AiSummaryRequest request) {
        try {
            AiSummaryResponse response = aiSummaryService.summarize(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping(value = "/ai/summary/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<StreamingResponseBody> aiSummaryStream(@RequestBody AiSummaryRequest request) {
        try {
            Flux<String> content = aiSummaryService.streamSummary(request);
            StreamingResponseBody body = outputStream -> {
                content.doOnNext(chunk -> {
                    try {
                        outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }).blockLast();
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header("X-AI-Model", aiSummaryService.modelName())
                    .body(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/ai/call-graph")
    ResponseEntity<?> aiCallGraph(@RequestBody AiCallGraphRequest request) {
        try {
            AiCallGraphResponse response = aiSummaryService.generateCallGraph(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/ai/flows")
    ResponseEntity<?> aiFlows(@RequestBody AiFlowRecommendationRequest request) {
        try {
            AiFlowRecommendationResponse response = aiSummaryService.recommendFlows(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/ai/context")
    ResponseEntity<?> aiCodingContext(@RequestBody AiCodingContextRequest request) {
        try {
            AiCodingContextResponse response = aiSummaryService.generateCodingContext(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping(value = "/ai/context/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<StreamingResponseBody> aiCodingContextStream(@RequestBody AiCodingContextRequest request) {
        try {
            Flux<String> content = aiSummaryService.streamCodingContext(request);
            StreamingResponseBody body = outputStream -> {
                content.doOnNext(chunk -> {
                    try {
                        outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }).blockLast();
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header("X-AI-Model", aiSummaryService.contextModelName())
                    .body(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}

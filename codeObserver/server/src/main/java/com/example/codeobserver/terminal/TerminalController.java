package com.example.codeobserver.terminal;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.example.codeobserver.terminal.TerminalDtos.CreateTerminalSessionRequest;
import static com.example.codeobserver.terminal.TerminalDtos.ResizeTerminalSessionRequest;
import static com.example.codeobserver.terminal.TerminalDtos.TerminalSessionListResponse;

@RestController
@RequestMapping("/api/terminal")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class TerminalController {
    private final TerminalSessionManager sessionManager;

    public TerminalController(TerminalSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @PostMapping("/sessions")
    ResponseEntity<?> create(@RequestBody CreateTerminalSessionRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Terminal API is only available from localhost."));
        }
        try {
            return ResponseEntity.ok(sessionManager.create(request).toResponse());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/sessions")
    ResponseEntity<?> list(@RequestParam(required = false) String root, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Terminal API is only available from localhost."));
        }
        return ResponseEntity.ok(new TerminalSessionListResponse(sessionManager.list(root)));
    }

    @PostMapping("/sessions/{id}/resize")
    ResponseEntity<?> resize(@PathVariable String id, @RequestBody ResizeTerminalSessionRequest request, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Terminal API is only available from localhost."));
        }
        try {
            sessionManager.resize(id, request.cols(), request.rows());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/sessions/{id}")
    ResponseEntity<Void> close(@PathVariable String id, HttpServletRequest servletRequest) {
        if (!isLocalRequest(servletRequest)) {
            return ResponseEntity.status(403).build();
        }
        sessionManager.close(id);
        return ResponseEntity.noContent().build();
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "localhost".equalsIgnoreCase(remoteAddress);
    }
}

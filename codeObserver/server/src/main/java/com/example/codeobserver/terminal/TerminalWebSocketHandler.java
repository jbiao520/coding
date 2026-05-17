package com.example.codeobserver.terminal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private static final CloseStatus SESSION_NOT_FOUND = new CloseStatus(4404, "Terminal session not found");

    private final TerminalSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public TerminalWebSocketHandler(TerminalSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (!isLocalOrigin(socket.getHandshakeHeaders()) || !isLocalRemote(socket)) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String terminalId = terminalId(socket.getUri());
        TerminalSession terminalSession = sessionManager.get(terminalId).orElse(null);
        if (terminalSession == null) {
            socket.close(SESSION_NOT_FOUND);
            return;
        }
        terminalSession.attach(socket);
        socket.getAttributes().put("terminalId", terminalId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        String terminalId = (String) socket.getAttributes().get("terminalId");
        TerminalSession terminalSession = terminalId == null ? null : sessionManager.get(terminalId).orElse(null);
        if (terminalSession == null) {
            socket.close(SESSION_NOT_FOUND);
            return;
        }
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
        });
        String type = String.valueOf(payload.get("type"));
        if ("input".equals(type)) {
            Object data = payload.get("data");
            if (data instanceof String value) {
                terminalSession.write(value);
            }
        } else if ("resize".equals(type)) {
            terminalSession.resize(asInt(payload.get("cols"), 120), asInt(payload.get("rows"), 32));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        String terminalId = (String) socket.getAttributes().get("terminalId");
        if (terminalId == null) {
            return;
        }
        sessionManager.get(terminalId).ifPresent(session -> session.detach(socket));
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) throws Exception {
        try {
            socket.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // Socket may already be gone.
        }
    }

    private String terminalId(URI uri) {
        if (uri == null) {
            return "";
        }
        String path = uri.getPath();
        String marker = "/api/terminal/sessions/";
        int start = path.indexOf(marker);
        if (start < 0) {
            return "";
        }
        String rest = path.substring(start + marker.length());
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private boolean isLocalOrigin(HttpHeaders headers) {
        String origin = headers.getOrigin();
        if (origin == null) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "0.0.0.0".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isLocalRemote(WebSocketSession socket) {
        InetSocketAddress remoteAddress = socket.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null && remoteAddress.getAddress().isLoopbackAddress();
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}

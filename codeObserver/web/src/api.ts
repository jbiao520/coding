import type {
  AiCallGraphRequest,
  AiCallGraphResponse,
  AiCodingContextRequest,
  AiCodingContextResponse,
  AiSummaryRequest,
  AiSummaryResponse,
  ProjectDetail,
  SourceResponse,
  WorkspaceSnapshot,
} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8088";

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function scanWorkspace(root: string): Promise<WorkspaceSnapshot> {
  const query = root.trim() ? `?root=${encodeURIComponent(root.trim())}` : "";
  return getJson<WorkspaceSnapshot>(`/api/workspace${query}`);
}

export function loadProject(id: string, root: string): Promise<ProjectDetail> {
  const query = root.trim() ? `?root=${encodeURIComponent(root.trim())}` : "";
  return getJson<ProjectDetail>(`/api/projects/${encodeURIComponent(id)}${query}`);
}

export function loadSource(path: string): Promise<SourceResponse> {
  return getJson<SourceResponse>(`/api/source?path=${encodeURIComponent(path)}`);
}

export function loadAiSummary(request: AiSummaryRequest): Promise<AiSummaryResponse> {
  return postJson<AiSummaryResponse>("/api/ai/summary", request);
}

export function loadAiCallGraph(request: AiCallGraphRequest): Promise<AiCallGraphResponse> {
  return postJson<AiCallGraphResponse>("/api/ai/call-graph", request);
}

export function loadAiCodingContext(request: AiCodingContextRequest): Promise<AiCodingContextResponse> {
  return postJson<AiCodingContextResponse>("/api/ai/context", request);
}

export async function streamAiSummary(
  request: AiSummaryRequest,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal,
): Promise<{ model: string }> {
  const response = await fetch(`${API_BASE}/api/ai/summary/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  if (!response.body) {
    throw new Error("Streaming response is not available in this browser.");
  }

  const model = response.headers.get("X-AI-Model") ?? "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    onChunk(decoder.decode(value, { stream: true }));
  }
  const rest = decoder.decode();
  if (rest) {
    onChunk(rest);
  }
  return { model };
}

export async function streamAiCodingContext(
  request: AiCodingContextRequest,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal,
): Promise<{ model: string }> {
  const response = await fetch(`${API_BASE}/api/ai/context/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  if (!response.body) {
    throw new Error("Streaming response is not available in this browser.");
  }

  const model = response.headers.get("X-AI-Model") ?? "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    onChunk(decoder.decode(value, { stream: true }));
  }
  const rest = decoder.decode();
  if (rest) {
    onChunk(rest);
  }
  return { model };
}

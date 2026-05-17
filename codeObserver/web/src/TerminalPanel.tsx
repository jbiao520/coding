import React, { useEffect, useMemo, useRef, useState } from "react";
import Button from "antd/es/button";
import { Plus, RotateCw, Terminal as TerminalIcon, X } from "lucide-react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal as XtermTerminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { closeTerminalSession, createTerminalSession, listTerminalSessions, resizeTerminalSession, terminalWebSocketUrl } from "./api";
import type { TerminalSessionInfo } from "./types";

type TerminalTabState = TerminalSessionInfo & {
  root: string;
};

type TerminalPanelProps = {
  root: string;
  projectId: string;
  projectPath: string;
  active: boolean;
};

const TERMINAL_TABS_STORAGE_KEY = "codeObserver.terminal.tabs";
const TERMINAL_ACTIVE_TAB_STORAGE_KEY = "codeObserver.terminal.activeTabId";

export function TerminalPanel({ root, projectId, projectPath, active }: TerminalPanelProps) {
  const [tabs, setTabs] = useState<TerminalTabState[]>(() => readTerminalTabs());
  const [activeTabId, setActiveTabId] = useState(() => readStringPreference(TERMINAL_ACTIVE_TAB_STORAGE_KEY, ""));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const activeTab = useMemo(
    () => tabs.find((tab) => tab.id === activeTabId) ?? tabs[0],
    [activeTabId, tabs],
  );

  useEffect(() => {
    writeTerminalTabs(tabs);
  }, [tabs]);

  useEffect(() => {
    writeStringPreference(TERMINAL_ACTIVE_TAB_STORAGE_KEY, activeTabId);
  }, [activeTabId]);

  useEffect(() => {
    if (!root) {
      return;
    }
    let cancelled = false;
    listTerminalSessions(root)
      .then((response) => {
        if (cancelled) {
          return;
        }
        setTabs((current) => mergePersistedTabs(root, current, response.sessions));
        setError("");
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [root]);

  useEffect(() => {
    if (!tabs.length) {
      setActiveTabId("");
      return;
    }
    if (!tabs.some((tab) => tab.id === activeTabId)) {
      setActiveTabId(tabs[0].id);
    }
  }, [activeTabId, tabs]);

  async function createSession(cwdOverride?: string, projectIdOverride?: string) {
    if (!root) {
      setError("请先扫描一个工作区。");
      return;
    }
    const cwd = cwdOverride || projectPath || root;
    setLoading(true);
    setError("");
    try {
      const session = await createTerminalSession({
        projectId: projectIdOverride || projectId,
        root,
        cwd,
        cols: 120,
        rows: 32,
      });
      const tab = { ...session, root, alive: true };
      setTabs((current) => [...current.filter((item) => item.id !== tab.id), tab]);
      setActiveTabId(tab.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建 Terminal 失败。");
    } finally {
      setLoading(false);
    }
  }

  async function closeTab(id: string) {
    setError("");
    setTabs((current) => current.filter((tab) => tab.id !== id));
    try {
      await closeTerminalSession(id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "关闭 Terminal 失败。");
    }
  }

  async function restartTab(tab: TerminalTabState) {
    setTabs((current) => current.filter((item) => item.id !== tab.id));
    await createSession(tab.cwd, tab.projectId);
  }

  function markExited(id: string) {
    setTabs((current) => current.map((tab) => tab.id === id ? { ...tab, alive: false } : tab));
  }

  return (
    <div className="terminal-panel">
      <div className="terminal-header">
        <div className="terminal-tabs" role="tablist" aria-label="Terminal sessions">
          {tabs.map((tab) => (
            <div
              key={tab.id}
              className={`terminal-tab ${tab.id === activeTab?.id ? "active" : ""} ${tab.alive ? "" : "dead"}`}
              role="tab"
              tabIndex={0}
              aria-selected={tab.id === activeTab?.id}
              onClick={() => setActiveTabId(tab.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setActiveTabId(tab.id);
                }
              }}
              title={tab.cwd}
            >
              <TerminalIcon size={13} />
              <span>{tab.title}</span>
              {!tab.alive ? <small>断开</small> : null}
              <Button
                className="terminal-tab-close"
                htmlType="button"
                size="small"
                title="关闭 Terminal"
                icon={<X size={12} />}
                onClick={(event) => {
                  event.stopPropagation();
                  void closeTab(tab.id);
                }}
              />
            </div>
          ))}
        </div>
        <div className="terminal-actions">
          <span title={activeTab?.cwd ?? projectPath ?? root}>{activeTab?.cwd ?? projectPath ?? root}</span>
          <Button
            htmlType="button"
            title="新建 Terminal"
            disabled={loading || !root}
            icon={loading ? <RotateCw size={14} /> : <Plus size={14} />}
            onClick={() => void createSession()}
          />
          {activeTab ? (
            <Button
              htmlType="button"
              title="关闭当前 Terminal"
              icon={<X size={14} />}
              onClick={() => void closeTab(activeTab.id)}
            />
          ) : null}
        </div>
      </div>
      {error ? <div className="terminal-error">{error}</div> : null}
      <div className="terminal-body">
        {!tabs.length ? (
          <div className="terminal-empty">
            <Button
              type="primary"
              htmlType="button"
              disabled={loading || !root}
              icon={loading ? <RotateCw size={15} /> : <Plus size={15} />}
              onClick={() => void createSession()}
            >
              创建 Terminal
            </Button>
          </div>
        ) : null}
        {tabs.map((tab) => (
          tab.alive ? (
            <TerminalView
              key={tab.id}
              session={tab}
              active={active && tab.id === activeTab?.id}
              onExit={() => markExited(tab.id)}
            />
          ) : (
            <div key={tab.id} className={`terminal-disconnected ${tab.id === activeTab?.id ? "active" : ""}`}>
              <div>
                <TerminalIcon size={22} />
                <strong>Terminal 已断开</strong>
                <span>{tab.cwd}</span>
                <Button htmlType="button" icon={<RotateCw size={14} />} onClick={() => void restartTab(tab)}>
                  重新启动
                </Button>
              </div>
            </div>
          )
        ))}
      </div>
    </div>
  );
}

function TerminalView({ session, active, onExit }: { session: TerminalTabState; active: boolean; onExit: () => void }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<XtermTerminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const activeRef = useRef(active);
  const sizeRef = useRef({ cols: 0, rows: 0 });

  useEffect(() => {
    activeRef.current = active;
    if (active) {
      window.setTimeout(() => fitAndResize(), 0);
    }
  }, [active]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    let disposed = false;

    const terminal = new XtermTerminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: 'JetBrains Mono, "SF Mono", Menlo, Consolas, monospace',
      fontSize: 12,
      lineHeight: 1.22,
      scrollback: 5000,
      theme: {
        background: "#11161d",
        foreground: "#d6dde3",
        cursor: "#f4c95d",
        black: "#11161d",
        brightBlack: "#59636f",
        blue: "#78a6ff",
        brightBlue: "#9ebeff",
        cyan: "#5dd8c8",
        brightCyan: "#8ce9df",
        green: "#7ad982",
        brightGreen: "#a3e8a8",
        magenta: "#d795ff",
        brightMagenta: "#e3b5ff",
        red: "#ff7f7a",
        brightRed: "#ffaaa6",
        white: "#d6dde3",
        brightWhite: "#ffffff",
        yellow: "#f4c95d",
        brightYellow: "#ffe18a",
      },
    });
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(container);
    terminal.focus();
    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;

    const socket = new WebSocket(terminalWebSocketUrl(session.id));
    socketRef.current = socket;
    socket.addEventListener("open", () => {
      fitAndResize();
    });
    socket.addEventListener("message", (event) => {
      const payload = parseSocketMessage(event.data);
      if (payload?.type === "output" && typeof payload.data === "string") {
        terminal.write(payload.data);
      } else if (payload?.type === "exit") {
        terminal.writeln("");
        terminal.writeln("\x1b[90m[process exited]\x1b[0m");
        onExit();
      }
    });
    socket.addEventListener("close", (event) => {
      if (!disposed && event.code !== 1000) {
        onExit();
      }
    });
    socket.addEventListener("error", () => {
      terminal.writeln("\r\n\x1b[31m[terminal websocket error]\x1b[0m");
    });

    const disposable = terminal.onData((data) => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "input", data }));
      }
    });
    const resizeDisposable = terminal.onResize(({ cols, rows }) => {
      sendResize(cols, rows);
    });
    const observer = new ResizeObserver(() => {
      if (activeRef.current) {
        fitAndResize();
      }
    });
    observer.observe(container);

    return () => {
      disposed = true;
      observer.disconnect();
      disposable.dispose();
      resizeDisposable.dispose();
      socket.close();
      terminal.dispose();
    };
  }, [session.id]);

  function fitAndResize() {
    const container = containerRef.current;
    const fitAddon = fitAddonRef.current;
    const terminal = terminalRef.current;
    if (!container || !fitAddon || !terminal || container.clientWidth <= 0 || container.clientHeight <= 0) {
      return;
    }
    fitAddon.fit();
    sendResize(terminal.cols, terminal.rows);
    terminal.focus();
  }

  function sendResize(cols: number, rows: number) {
    if (!cols || !rows || (sizeRef.current.cols === cols && sizeRef.current.rows === rows)) {
      return;
    }
    sizeRef.current = { cols, rows };
    const socket = socketRef.current;
    const payload = JSON.stringify({ type: "resize", cols, rows });
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(payload);
    } else {
      void resizeTerminalSession(session.id, cols, rows).catch(() => undefined);
    }
  }

  return (
    <div
      className={`terminal-view ${active ? "active" : ""}`}
      ref={containerRef}
      onMouseDown={() => terminalRef.current?.focus()}
    />
  );
}

function mergePersistedTabs(root: string, current: TerminalTabState[], liveSessions: TerminalSessionInfo[]) {
  const stored = current.length ? current : readTerminalTabs();
  const liveById = new Map(liveSessions.map((session) => [session.id, session]));
  const next = stored
    .filter((tab) => tab.root === root)
    .map((tab) => {
      const live = liveById.get(tab.id);
      return live ? { ...live, root, alive: true } : { ...tab, alive: false };
    });
  liveSessions.forEach((session) => {
    if (!next.some((tab) => tab.id === session.id)) {
      next.push({ ...session, root, alive: true });
    }
  });
  return next;
}

function parseSocketMessage(data: unknown): { type: string; data?: string; exitCode?: number } | null {
  if (typeof data !== "string") {
    return null;
  }
  try {
    return JSON.parse(data) as { type: string; data?: string; exitCode?: number };
  } catch {
    return null;
  }
}

function readTerminalTabs(): TerminalTabState[] {
  try {
    const rawValue = window.localStorage.getItem(TERMINAL_TABS_STORAGE_KEY);
    if (!rawValue) {
      return [];
    }
    const parsed = JSON.parse(rawValue);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter(isTerminalTabState);
  } catch {
    return [];
  }
}

function writeTerminalTabs(tabs: TerminalTabState[]) {
  try {
    window.localStorage.setItem(TERMINAL_TABS_STORAGE_KEY, JSON.stringify(tabs));
  } catch {
    // Terminal tab metadata is best-effort UI persistence.
  }
}

function isTerminalTabState(value: unknown): value is TerminalTabState {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<TerminalTabState>;
  return Boolean(candidate.id && candidate.title && candidate.cwd && candidate.createdAt);
}

function readStringPreference(key: string, fallback: string) {
  try {
    return window.localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

function writeStringPreference(key: string, value: string) {
  try {
    if (value) {
      window.localStorage.setItem(key, value);
    } else {
      window.localStorage.removeItem(key);
    }
  } catch {
    // Best-effort UI preference only.
  }
}

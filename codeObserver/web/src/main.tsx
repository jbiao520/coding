import React, { useEffect, useMemo, useState } from "react";
import ReactDOM from "react-dom/client";
import ReactMarkdown from "react-markdown";
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  BookOpen,
  Braces,
  ChevronRight,
  Clipboard,
  ClipboardCheck,
  FileCode2,
  FolderSearch,
  GitBranch,
  LocateFixed,
  PanelBottomClose,
  PanelBottomOpen,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  Route,
  Search,
  Send,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { loadAiCallGraph, loadProject, loadSource, scanWorkspace, streamAiCodingContext, streamAiSummary } from "./api";
import type {
  AiCallGraphResponse,
  ClassInfo,
  GraphEdge,
  GraphNode,
  MethodInfo,
  ProjectDetail,
  ProjectSummary,
  SourceResponse,
} from "./types";
import "./styles.css";

type Selection = {
  nodeId?: string;
  filePath?: string;
  line?: number;
};

type BusinessTrace = {
  id: string;
  title: string;
  summary: string;
  nodeIds: string[];
};

type GraphMode = "code" | "ai";
type DrawerTab = "source" | "ai";

type AiOutputMode = "explain" | "context";

type SourceLink = {
  id: string;
  label: string;
  subtitle: string;
  filePath: string;
  line: number;
};

type MethodExplanation = {
  label: string;
  text: string;
};

function App() {
  const [root, setRoot] = useState("");
  const [workspaceRoot, setWorkspaceRoot] = useState("");
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>("");
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [source, setSource] = useState<SourceResponse | null>(null);
  const [selection, setSelection] = useState<Selection>({});
  const [selectedTraceId, setSelectedTraceId] = useState("");
  const [workspaceDrawerOpen, setWorkspaceDrawerOpen] = useState(false);
  const [activeDrawerTab, setActiveDrawerTab] = useState<DrawerTab>("source");
  const [tracePanelCollapsed, setTracePanelCollapsed] = useState(false);
  const [projectsCollapsed, setProjectsCollapsed] = useState(false);
  const [query, setQuery] = useState("");
  const [aiQuestion, setAiQuestion] = useState("");
  const [aiOutputMode, setAiOutputMode] = useState<AiOutputMode>("explain");
  const [aiSummary, setAiSummary] = useState("");
  const [aiModel, setAiModel] = useState("");
  const [aiLoading, setAiLoading] = useState(false);
  const [aiError, setAiError] = useState("");
  const [aiContext, setAiContext] = useState("");
  const [aiContextModel, setAiContextModel] = useState("");
  const [aiContextLoading, setAiContextLoading] = useState(false);
  const [aiContextError, setAiContextError] = useState("");
  const [aiContextCopied, setAiContextCopied] = useState(false);
  const [graphMode, setGraphMode] = useState<GraphMode>("code");
  const [aiGraph, setAiGraph] = useState<AiCallGraphResponse | null>(null);
  const [aiGraphLoading, setAiGraphLoading] = useState(false);
  const [aiGraphError, setAiGraphError] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void handleScan("");
  }, []);

  useEffect(() => {
    if (!selectedProjectId) {
      setDetail(null);
      setGraphMode("code");
      setAiGraph(null);
      return;
    }
    setLoading(true);
    setError("");
    loadProject(selectedProjectId, workspaceRoot || root)
      .then((nextDetail) => {
        setDetail(nextDetail);
        setAiQuestion("");
        setAiOutputMode("explain");
        setAiSummary("");
        setAiModel("");
        setAiError("");
        setAiContext("");
        setAiContextModel("");
        setAiContextLoading(false);
        setAiContextError("");
        setAiContextCopied(false);
        setGraphMode("code");
        setAiGraph(null);
        setAiGraphError("");
        const traces = buildBusinessTraces(nextDetail);
        setSelectedTraceId(traces[0]?.id ?? "");
        const firstStep = nextDetail.readingPath[0];
        const firstNode = nextDetail.graphNodes[0];
        const firstTraceNode = traces[0]?.nodeIds[0];
        const firstTraceGraphNode = firstTraceNode ? nextDetail.graphNodes.find((node) => node.id === firstTraceNode) : undefined;
        if (firstTraceGraphNode) {
          setSelection({ nodeId: firstTraceGraphNode.id, filePath: firstTraceGraphNode.filePath, line: firstTraceGraphNode.line });
        } else if (firstStep) {
          setSelection({ nodeId: firstStep.targetNodeId, filePath: firstStep.filePath, line: firstStep.line });
        } else if (firstNode) {
          setSelection({ nodeId: firstNode.id, filePath: firstNode.filePath, line: firstNode.line });
        }
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, [selectedProjectId, workspaceRoot, root]);

  useEffect(() => {
    if (!selection.filePath) {
      setSource(null);
      return;
    }
    loadSource(selection.filePath)
      .then(setSource)
      .catch((err: Error) => setError(err.message));
  }, [selection.filePath]);

  function handleScan(nextRoot = root) {
    setLoading(true);
    setError("");
    scanWorkspace(nextRoot)
      .then((snapshot) => {
        setWorkspaceRoot(snapshot.root);
        setRoot(snapshot.root);
        setProjects(snapshot.projects);
        setSelectedProjectId((current) => {
          if (current && snapshot.projects.some((project) => project.id === current)) {
            return current;
          }
          return snapshot.projects[0]?.id ?? "";
        });
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }

  const selectedNode = useMemo(
    () => detail?.graphNodes.find((node) => node.id === selection.nodeId),
    [detail, selection.nodeId],
  );

  const filteredClasses = useMemo(() => {
    if (!detail) {
      return [];
    }
    const normalized = query.trim().toLowerCase();
    if (!normalized) {
      return detail.classes;
    }
    return detail.classes.filter((classInfo) => {
      const target = [
        classInfo.name,
        classInfo.qualifiedName,
        classInfo.concepts.join(" "),
        classInfo.methods.map((method) => method.name).join(" "),
      ].join(" ").toLowerCase();
      return target.includes(normalized);
    });
  }, [detail, query]);

  const businessTraces = useMemo(() => (detail ? buildBusinessTraces(detail) : []), [detail]);
  const selectedTrace = useMemo(
    () => businessTraces.find((trace) => trace.id === selectedTraceId),
    [businessTraces, selectedTraceId],
  );

  const aiSourceLinks = useMemo(() => {
    if (!detail) {
      return [];
    }
    const nodeIds = selectedTrace?.nodeIds.length
      ? selectedTrace.nodeIds
      : selection.nodeId
        ? [selection.nodeId]
        : [];
    return nodeIds
      .map((nodeId) => {
        const node = detail.graphNodes.find((candidate) => candidate.id === nodeId);
        if (!node) {
          return null;
        }
        return {
          id: node.id,
          label: ownerClassName(detail, node.id) ? `${ownerClassName(detail, node.id)}.${node.label}` : node.label,
          subtitle: graphNodeSubtitle(detail, node),
          filePath: node.filePath,
          line: node.line,
        };
      })
      .filter((link): link is SourceLink => Boolean(link));
  }, [detail, selectedTrace, selection.nodeId]);

  const aiMethodExplanations = useMemo(
    () => buildAiMethodExplanations(detail, selectedTrace, selection.nodeId, aiSummary, aiGraph),
    [detail, selectedTrace, selection.nodeId, aiSummary, aiGraph],
  );

  const sourceTabActive = workspaceDrawerOpen && activeDrawerTab === "source";
  const aiTabActive = workspaceDrawerOpen && activeDrawerTab === "ai";

  function openSourceDrawer() {
    setActiveDrawerTab("source");
    setWorkspaceDrawerOpen(true);
  }

  function toggleSourceDrawer() {
    if (sourceTabActive) {
      setWorkspaceDrawerOpen(false);
      return;
    }
    openSourceDrawer();
  }

  function selectNode(
    nodeId: string,
    filePath: string,
    line: number,
    options: { preserveAi?: boolean; preserveTrace?: boolean; preserveAiGraph?: boolean } = {},
  ) {
    if (!options.preserveTrace) {
      setSelectedTraceId("");
    }
    setSelection({ nodeId, filePath, line });
    if (!options.preserveAi) {
      setAiOutputMode("explain");
      setAiSummary("");
      setAiModel("");
      setAiError("");
      setAiContext("");
      setAiContextModel("");
      setAiContextError("");
      setAiContextCopied(false);
    }
    if (!options.preserveAiGraph) {
      setGraphMode("code");
      setAiGraph(null);
      setAiGraphError("");
    }
  }

  function selectTrace(trace: BusinessTrace) {
    setSelectedTraceId(trace.id);
    setAiOutputMode("explain");
    setAiSummary("");
    setAiModel("");
    setAiError("");
    setAiContext("");
    setAiContextModel("");
    setAiContextError("");
    setAiContextCopied(false);
    setGraphMode("code");
    setAiGraph(null);
    setAiGraphError("");
    const firstNode = detail?.graphNodes.find((node) => node.id === trace.nodeIds[0]);
    if (firstNode) {
      setSelection({ nodeId: firstNode.id, filePath: firstNode.filePath, line: firstNode.line });
    }
  }

  function handleAiSummary(question = aiQuestion) {
    if (!detail || !selectedProjectId) {
      return;
    }
    setAiOutputMode("explain");
    setAiLoading(true);
    setAiError("");
    setAiSummary("");
    setAiModel("");
    streamAiSummary({
      projectId: selectedProjectId,
      root: workspaceRoot || root,
      selectedNodeId: selection.nodeId,
      trace: selectedTrace,
      question,
    }, (chunk) => setAiSummary((current) => current + chunk))
      .then((response) => {
        setAiModel(response.model);
      })
      .catch((err: Error) => setAiError(readableError(err.message)))
      .finally(() => setAiLoading(false));
  }

  function handleAiCodingContext(task = aiQuestion) {
    if (!detail || !selectedProjectId) {
      return;
    }
    setAiOutputMode("context");
    setAiContextLoading(true);
    setAiContextError("");
    setAiContext("");
    setAiContextModel("");
    setAiContextCopied(false);
    streamAiCodingContext({
      projectId: selectedProjectId,
      root: workspaceRoot || root,
      selectedNodeId: selection.nodeId,
      trace: selectedTrace,
      task,
    }, (chunk) => setAiContext((current) => current + chunk))
      .then((response) => {
        setAiContextModel(response.model);
      })
      .catch((err: Error) => setAiContextError(readableError(err.message)))
      .finally(() => setAiContextLoading(false));
  }

  function handleAiCallGraph() {
    if (!detail || !selectedProjectId) {
      return;
    }
    if (graphMode === "ai" && aiGraph && !aiGraphLoading) {
      setGraphMode("code");
      return;
    }
    setGraphMode("ai");
    setAiGraphLoading(true);
    setAiGraphError("");
    loadAiCallGraph({
      projectId: selectedProjectId,
      root: workspaceRoot || root,
      selectedNodeId: selection.nodeId,
      trace: selectedTrace,
    })
      .then((nextGraph) => {
        setAiGraph(nextGraph);
      })
      .catch((err: Error) => setAiGraphError(readableError(err.message)))
      .finally(() => setAiGraphLoading(false));
  }

  function openAiDrawer(question = "", force = false) {
    setActiveDrawerTab("ai");
    setWorkspaceDrawerOpen(true);
    setAiOutputMode("explain");
    if (aiLoading) {
      return;
    }
    if (!force && !question && aiSummary) {
      return;
    }
    handleAiSummary(question);
  }

  function openContextDrawer(force = false) {
    setActiveDrawerTab("ai");
    setWorkspaceDrawerOpen(true);
    setAiOutputMode("context");
    if (aiContextLoading) {
      return;
    }
    if (!force && aiContext) {
      return;
    }
    handleAiCodingContext(aiQuestion);
  }

  function clearAiHistory() {
    setAiSummary("");
    setAiModel("");
    setAiError("");
    setAiContext("");
    setAiContextModel("");
    setAiContextError("");
    setAiContextCopied(false);
  }

  async function copyAiContext() {
    if (!aiContext) {
      return;
    }
    try {
      await navigator.clipboard.writeText(aiContext);
      setAiContextCopied(true);
      window.setTimeout(() => setAiContextCopied(false), 1400);
    } catch {
      setAiContextError("复制失败，请手动选中 Context 内容复制。");
    }
  }

  function startRecommendedReading() {
    const firstTrace = businessTraces[0];
    if (firstTrace) {
      selectTrace(firstTrace);
      openSourceDrawer();
      return;
    }
    const firstStep = detail?.readingPath[0];
    if (firstStep) {
      selectNode(firstStep.targetNodeId, firstStep.filePath, firstStep.line);
      openSourceDrawer();
    }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark">
            <GitBranch size={18} />
          </div>
          <div>
            <h1>Code Observer</h1>
            <p>把任意 Java 目录拆成源码地图、调用图和阅读路径。</p>
          </div>
        </div>
        <form
          className="root-form"
          onSubmit={(event) => {
            event.preventDefault();
            void handleScan(root);
          }}
        >
          <FolderSearch size={17} />
          <input
            aria-label="源码根目录"
            value={root}
            onChange={(event) => setRoot(event.target.value)}
            placeholder="/path/to/workspace"
          />
          <button type="submit" title="扫描目录">
            <RefreshCw size={16} />
            <span>扫描</span>
          </button>
        </form>
      </header>

      {error ? <div className="error-bar">{error}</div> : null}

      <section
        className={[
          "workspace-grid",
          projectsCollapsed ? "projects-collapsed" : "",
          workspaceDrawerOpen ? "drawer-open" : "",
        ].join(" ")}
      >
        <aside className="projects-panel">
          <div className="panel-header">
            {!projectsCollapsed ? <PanelTitle icon={<BookOpen size={16} />} title="项目" /> : null}
            <button
              className="panel-collapse-button"
              type="button"
              onClick={() => setProjectsCollapsed((collapsed) => !collapsed)}
              title={projectsCollapsed ? "展开项目" : "折叠项目"}
            >
              {projectsCollapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
            </button>
          </div>
          {projectsCollapsed ? (
            <div className="collapsed-rail">
              <BookOpen size={17} />
              <span>项目</span>
            </div>
          ) : (
            <div className="project-list">
              {projects.map((project) => (
                <button
                  key={project.id}
                  className={`project-item ${project.id === selectedProjectId ? "selected" : ""}`}
                  onClick={() => setSelectedProjectId(project.id)}
                >
                  <span className="project-name">{project.name}</span>
                  <span className="project-meta">
                    {project.buildType} · {project.javaFileCount} 个文件 · {project.methodCount} 个方法
                  </span>
                  <Concepts concepts={project.concepts.slice(0, 4)} />
                </button>
              ))}
            </div>
          )}
        </aside>

        <aside className="navigator-panel">
          <PanelTitle icon={<Search size={16} />} title="结构" />
          <div className="search-box">
            <Search size={15} />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索类、方法、概念" />
          </div>
          <div className="class-tree">
            {filteredClasses.map((classInfo) => (
              <ClassTree
                key={classInfo.id}
                classInfo={classInfo}
                selectedNodeId={selection.nodeId}
                onSelect={selectNode}
              />
            ))}
          </div>
        </aside>

        <section className={`center-panel ${tracePanelCollapsed ? "trace-collapsed" : ""}`}>
          <div className="graph-header">
            <div>
              <PanelTitle icon={<Route size={16} />} title="调用图" />
              <p>
                {detail
                  ? graphMode === "ai"
                    ? `${detail.summary.name} · ${aiGraph?.title ?? "AI 正在精简链路"}`
                    : `${detail.summary.name} · ${selectedTrace ? "业务调用链" : "聚焦当前选中的类或方法"}`
                  : "等待扫描"}
              </p>
            </div>
            <div className="graph-actions">
              <button
                className={`ai-graph-trigger ${graphMode === "ai" ? "active" : ""}`}
                type="button"
                onClick={handleAiCallGraph}
                disabled={!detail || aiGraphLoading}
                title={graphMode === "ai" && aiGraph ? "切回源码生成的调用图" : "AI 生成解释版调用图"}
              >
                <Sparkles size={15} />
                <span>{aiGraphLoading ? "生成中" : graphMode === "ai" && aiGraph ? "源码图" : "AI 生成图"}</span>
              </button>
              <button
                className={`ai-summary-trigger ${aiTabActive && aiOutputMode === "explain" ? "active" : ""}`}
                type="button"
                onClick={() => openAiDrawer("")}
                disabled={!detail}
                title={aiTabActive && aiOutputMode === "explain" ? "AI 解释已打开" : "AI 解释当前链路"}
              >
                <Sparkles size={15} />
                <span>{aiLoading ? "生成中" : "AI 解释"}</span>
              </button>
              <button
                className={`ai-context-trigger ${aiTabActive && aiOutputMode === "context" ? "active" : ""}`}
                type="button"
                onClick={() => openContextDrawer()}
                disabled={!detail}
                title={aiTabActive && aiOutputMode === "context" ? "Context 已打开" : "生成可粘贴给代码模型的链路 Context"}
              >
                <Clipboard size={15} />
                <span>{aiContextLoading ? "生成中" : "Context"}</span>
              </button>
              <button
                className={`source-toggle ${sourceTabActive ? "active" : ""}`}
                type="button"
                onClick={toggleSourceDrawer}
                title={sourceTabActive ? "关闭源码抽屉" : "显示源码"}
              >
                {sourceTabActive ? <PanelRightClose size={15} /> : <PanelRightOpen size={15} />}
                <span>源码</span>
              </button>
              <div className="status-pill" title={loading ? "加载中" : "已就绪"}>{loading ? "加载中" : "就绪"}</div>
            </div>
          </div>
          <ReadingGuide
            detail={detail}
            selectedTrace={selectedTrace}
            sourceActive={sourceTabActive}
            aiActive={aiTabActive}
            loading={loading}
            onStart={startRecommendedReading}
            onOpenSource={openSourceDrawer}
            onOpenAi={() => openAiDrawer("")}
          />
          <GraphCanvas
            detail={detail}
            selectedNodeId={selection.nodeId}
            selectedTrace={selectedTrace}
            aiGraph={graphMode === "ai" ? aiGraph : null}
            aiGraphLoading={aiGraphLoading}
            aiGraphError={graphMode === "ai" ? aiGraphError : ""}
            onSelect={(node) => selectNode(
              node.id,
              node.filePath,
              node.line,
              graphMode === "ai" ? { preserveAi: true, preserveTrace: true, preserveAiGraph: true } : {},
            )}
          />
          <TraceExplorer
            detail={detail}
            traces={businessTraces}
            selectedTraceId={selectedTraceId}
            selectedNodeId={selection.nodeId}
            collapsed={tracePanelCollapsed}
            onToggleCollapsed={() => setTracePanelCollapsed((collapsed) => !collapsed)}
            onSelectNode={selectNode}
            onSelectTrace={selectTrace}
          />
        </section>

        <aside className={`workspace-drawer side-drawer ${workspaceDrawerOpen ? "open" : ""}`} aria-hidden={!workspaceDrawerOpen}>
          <div className="drawer-header">
            <div className="drawer-tabs" role="tablist" aria-label="源码和 AI 解释">
              <button
                type="button"
                role="tab"
                aria-selected={activeDrawerTab === "source"}
                className={activeDrawerTab === "source" ? "active" : ""}
                onClick={() => setActiveDrawerTab("source")}
                title="源码"
              >
                <FileCode2 size={15} />
                <span>源码</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeDrawerTab === "ai"}
                className={activeDrawerTab === "ai" ? "active" : ""}
                onClick={() => setActiveDrawerTab("ai")}
                title="AI 解释"
              >
                <Sparkles size={15} />
                <span>AI 解释</span>
              </button>
            </div>
            <button type="button" onClick={() => setWorkspaceDrawerOpen(false)} title="关闭抽屉">
              <X size={16} />
            </button>
          </div>
          <div className="drawer-body">
            {activeDrawerTab === "source" ? (
              <div className="tab-panel source-tab-panel" role="tabpanel">
                <SelectionSummary node={selectedNode} detail={detail} />
                <SourceViewer
                  source={source}
                  highlightLine={selection.line}
                  selectedNode={selectedNode}
                  detail={detail}
                  methodExplanations={aiMethodExplanations}
                />
              </div>
            ) : (
              <div className="tab-panel ai-tab-panel" role="tabpanel">
                <AiAssistantPanel
                  question={aiQuestion}
                  outputMode={aiOutputMode}
                  summary={aiSummary}
                  model={aiModel}
                  loading={aiLoading}
                  error={aiError}
                  context={aiContext}
                  contextModel={aiContextModel}
                  contextLoading={aiContextLoading}
                  contextError={aiContextError}
                  contextCopied={aiContextCopied}
                  disabled={!detail}
                  sourceLinks={aiSourceLinks}
                  onQuestionChange={setAiQuestion}
                  onAsk={() => openAiDrawer(aiQuestion, true)}
                  onGenerateContext={() => openContextDrawer(true)}
                  onCopyContext={copyAiContext}
                  onClearHistory={clearAiHistory}
                  onOpenSource={(link) => {
                    selectNode(link.id, link.filePath, link.line, { preserveAi: true, preserveTrace: true, preserveAiGraph: graphMode === "ai" });
                    openSourceDrawer();
                  }}
                />
              </div>
            )}
          </div>
        </aside>
      </section>
    </main>
  );
}

function PanelTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="panel-title">
      {icon}
      <h2>{title}</h2>
    </div>
  );
}

function Concepts({ concepts }: { concepts: string[] }) {
  if (!concepts.length) {
    return null;
  }
  return (
    <div className="concepts">
      {concepts.map((concept) => (
        <span key={concept}>{concept}</span>
      ))}
    </div>
  );
}

function ReadingGuide({
  detail,
  selectedTrace,
  sourceActive,
  aiActive,
  loading,
  onStart,
  onOpenSource,
  onOpenAi,
}: {
  detail: ProjectDetail | null;
  selectedTrace?: BusinessTrace;
  sourceActive: boolean;
  aiActive: boolean;
  loading: boolean;
  onStart: () => void;
  onOpenSource: () => void;
  onOpenAi: () => void;
}) {
  const projectLabel = detail
    ? `${detail.summary.classCount} 个类型 · ${detail.summary.methodCount} 个方法 · ${detail.summary.entryPoints.length} 个入口`
    : loading
      ? "正在扫描目录"
      : "扫描目录后会自动推荐阅读入口";
  return (
    <section className="reading-guide">
      <div>
        <strong>{selectedTrace ? `推荐从 ${selectedTrace.title} 开始` : "先沿推荐入口读一遍主流程"}</strong>
        <span>{selectedTrace?.summary ?? projectLabel}</span>
      </div>
      <div className="guide-actions">
        <button type="button" onClick={onStart} disabled={!detail} title="选中推荐链路并打开源码">
          开始阅读
        </button>
        <button type="button" onClick={onOpenSource} disabled={!detail} title="打开源码面板">
          {sourceActive ? "源码已打开" : "看源码"}
        </button>
        <button type="button" onClick={onOpenAi} disabled={!detail || loading} title="解释当前链路">
          {aiActive ? "AI 已打开" : "让 AI 解释"}
        </button>
      </div>
    </section>
  );
}

function ClassTree({
  classInfo,
  selectedNodeId,
  onSelect,
}: {
  classInfo: ClassInfo;
  selectedNodeId?: string;
  onSelect: (nodeId: string, filePath: string, line: number) => void;
}) {
  return (
    <div className="class-node">
      <button
        className={`tree-row class-row ${selectedNodeId === classInfo.id ? "active" : ""}`}
        onClick={() => onSelect(classInfo.id, classInfo.filePath, classInfo.beginLine)}
      >
        <Braces size={14} />
        <span>{classInfo.name}</span>
        <small>{localizedKind(classInfo.kind)}</small>
      </button>
      <div className="method-list">
        {classInfo.methods.map((method) => (
          <button
            key={method.id}
            className={`tree-row method-row ${selectedNodeId === method.id ? "active" : ""}`}
            onClick={() => onSelect(method.id, classInfo.filePath, method.beginLine)}
          >
            <ChevronRight size={13} />
            <span>{method.name}</span>
            <small>{method.calls.length ? `${method.calls.length} 次调用` : method.returnType}</small>
          </button>
        ))}
      </div>
    </div>
  );
}

function GraphCanvas({
  detail,
  selectedNodeId,
  selectedTrace,
  aiGraph,
  aiGraphLoading,
  aiGraphError,
  onSelect,
}: {
  detail: ProjectDetail | null;
  selectedNodeId?: string;
  selectedTrace?: BusinessTrace;
  aiGraph: AiCallGraphResponse | null;
  aiGraphLoading: boolean;
  aiGraphError: string;
  onSelect: (node: GraphNode) => void;
}) {
  const graph = useMemo(() => {
    if (!detail) {
      return { nodes: [] as Node[], edges: [] as Edge[], scope: "等待选择" };
    }
    if (aiGraph) {
      return buildAiGraph(detail, aiGraph, selectedNodeId);
    }
    const focused = selectedTrace ? buildTraceGraph(detail, selectedTrace) : buildFocusedGraph(detail, selectedNodeId);
    const classNodes = focused.nodes.filter((node) => node.type === "class");
    const methodNodes = focused.nodes.filter((node) => node.type === "method");
    const positionOf = new Map<string, { x: number; y: number }>();
    if (focused.mode === "trace") {
      methodNodes.forEach((node, index) => positionOf.set(node.id, { x: 48 + index * 290, y: 156 }));
      classNodes.forEach((node, index) => positionOf.set(node.id, { x: 48 + index * 290, y: 38 }));
    } else if (focused.mode === "method") {
      classNodes.forEach((node, index) => positionOf.set(node.id, { x: 330 + index * 250, y: 24 }));
      methodNodes.forEach((node, index) => {
        if (node.id === focused.centerId) {
          positionOf.set(node.id, { x: 360, y: 190 });
        } else {
          const isIncoming = focused.incomingIds.has(node.id);
          const sideIndex = isIncoming ? focused.incomingIdsArray.indexOf(node.id) : focused.outgoingIdsArray.indexOf(node.id);
          positionOf.set(node.id, {
            x: isIncoming ? 36 : 720,
            y: 88 + Math.max(0, sideIndex) * 92,
          });
        }
      });
    } else {
      classNodes.forEach((node, index) => positionOf.set(node.id, { x: 36 + index * 250, y: 42 }));
      methodNodes.forEach((node, index) => positionOf.set(node.id, { x: 330 + (index % 2) * 290, y: 44 + Math.floor(index / 2) * 92 }));
    }

    return {
      scope: focused.scope,
      nodes: focused.nodes.map((node) => ({
        id: node.id,
        position: positionOf.get(node.id) ?? { x: 0, y: 0 },
        data: {
          label: (
            <div className={`flow-node ${node.type} ${node.id === selectedNodeId ? "selected" : ""}`}>
              {selectedTrace ? <em>{selectedTrace.nodeIds.indexOf(node.id) + 1}</em> : null}
              <strong>{node.label}</strong>
              <span>{node.type === "class" ? localizedKind(String(node.data.kind ?? "class")) : graphNodeSubtitle(detail, node)}</span>
            </div>
          ),
        },
        type: "default",
      })),
      edges: focused.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: focused.mode === "trace" ? "" : edge.type === "CALLS" ? edge.label : "",
        animated: edge.type === "CALLS",
        markerEnd: { type: MarkerType.ArrowClosed },
        className: `edge-${edge.type.toLowerCase()}`,
        style: edge.type === "CALLS" ? { strokeWidth: 2 } : undefined,
      })),
    };
  }, [aiGraph, detail, selectedNodeId, selectedTrace]);

  if (!detail) {
    return <div className="empty-state">扫描目录后会在这里显示结构图。</div>;
  }

  return (
    <div className="graph-canvas">
      <div className="graph-scope">{graph.scope}</div>
      {aiGraphLoading ? <div className="graph-feedback">AI 正在挑重点、写解释...</div> : null}
      {aiGraphError ? <div className="graph-feedback error">{aiGraphError}</div> : null}
      <ReactFlow
        nodes={graph.nodes}
        edges={graph.edges}
        fitView
        fitViewOptions={{ padding: 0.24 }}
        minZoom={0.2}
        maxZoom={1.2}
        onNodeClick={(_, node) => {
          const graphNode = detail.graphNodes.find((candidate) => candidate.id === node.id);
          if (graphNode) {
            onSelect(graphNode);
          }
        }}
      >
        <Background />
        {graph.nodes.length > 8 ? <MiniMap pannable zoomable /> : null}
        <Controls />
      </ReactFlow>
    </div>
  );
}

function buildAiGraph(detail: ProjectDetail, aiGraph: AiCallGraphResponse, selectedNodeId?: string) {
  const sourceNodesById = new Map(detail.graphNodes.map((node) => [node.id, node]));
  const visibleNodes = aiGraph.nodes.filter((node) => sourceNodesById.has(node.id));
  const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
  const columns = visibleNodes.length <= 4 ? Math.max(visibleNodes.length, 1) : Math.ceil(visibleNodes.length / 2);
  return {
    scope: `${aiGraph.title} · ${visibleNodes.length} 个关键节点 · ${aiGraph.summary}`,
    nodes: visibleNodes.map((node, index) => {
      const sourceNode = sourceNodesById.get(node.id);
      return {
        id: node.id,
        position: {
          x: 56 + (index % columns) * 330,
          y: 76 + Math.floor(index / columns) * 176,
        },
        data: {
          label: (
            <div className={`flow-node ai-generated ${node.importance} ${node.id === selectedNodeId ? "selected" : ""}`}>
              <em>{index + 1}</em>
              <strong>{node.label}</strong>
              <span>{node.subtitle || (sourceNode ? graphNodeSubtitle(detail, sourceNode) : "关键步骤")}</span>
              <p>{node.explanation}</p>
            </div>
          ),
        },
        style: { width: 280 },
        type: "default",
      } satisfies Node;
    }),
    edges: aiGraph.edges
      .filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target))
      .map((edge) => ({
        id: edge.id || `ai:${edge.source}->${edge.target}`,
        source: edge.source,
        target: edge.target,
        label: edge.label,
        animated: false,
        markerEnd: { type: MarkerType.ArrowClosed },
        className: "edge-ai",
        style: { strokeWidth: 2.4 },
      } satisfies Edge)),
  };
}

type FocusedGraph = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  scope: string;
  mode: "class" | "method" | "trace";
  centerId?: string;
  incomingIds: Set<string>;
  incomingIdsArray: string[];
  outgoingIdsArray: string[];
};

function buildTraceGraph(detail: ProjectDetail, trace: BusinessTrace): FocusedGraph {
  const nodeIds = new Set(trace.nodeIds);
  const nodes = detail.graphNodes.filter((node) => nodeIds.has(node.id));
  const chainEdges: GraphEdge[] = [];
  for (let index = 0; index < trace.nodeIds.length - 1; index++) {
    const source = trace.nodeIds[index];
    const target = trace.nodeIds[index + 1];
    const edge = detail.graphEdges.find((candidate) => candidate.type === "CALLS" && candidate.source === source && candidate.target === target);
    if (edge) {
      chainEdges.push(edge);
    }
  }
  return {
    nodes,
    edges: chainEdges,
    scope: `${trace.title} · ${trace.nodeIds.length} 个步骤`,
    mode: "trace",
    centerId: trace.nodeIds[0],
    incomingIds: new Set<string>(),
    incomingIdsArray: [],
    outgoingIdsArray: trace.nodeIds.slice(1),
  };
}

function buildFocusedGraph(detail: ProjectDetail, selectedNodeId?: string): FocusedGraph {
  const nodesById = new Map(detail.graphNodes.map((node) => [node.id, node]));
  const selected = selectedNodeId ? nodesById.get(selectedNodeId) : detail.graphNodes[0];
  if (!selected) {
    return emptyFocusedGraph("没有可显示的节点");
  }

  if (selected.type === "class") {
    const classInfo = detail.classes.find((item) => item.id === selected.id);
    if (!classInfo) {
      return singleNodeGraph(selected);
    }
    const methodIds = new Set(classInfo.methods.map((method) => method.id));
    const allowedIds = new Set<string>([classInfo.id, ...methodIds]);
    const edges = detail.graphEdges.filter((edge) => {
      if (edge.type === "DECLARES") {
        return edge.source === classInfo.id && methodIds.has(edge.target);
      }
      if (edge.type === "CALLS") {
        return methodIds.has(edge.source) && methodIds.has(edge.target);
      }
      return allowedIds.has(edge.source) && allowedIds.has(edge.target);
    });
    return {
      nodes: detail.graphNodes.filter((node) => allowedIds.has(node.id)),
      edges,
      scope: `${classInfo.name} · ${methodIds.size} 个方法 · ${edges.filter((edge) => edge.type === "CALLS").length} 次内部调用`,
      mode: "class",
      incomingIds: new Set<string>(),
      incomingIdsArray: [],
      outgoingIdsArray: [],
    };
  }

  const ownerClass = detail.classes.find((item) => item.methods.some((method) => method.id === selected.id));
  const touchingEdges = detail.graphEdges.filter((edge) => edge.source === selected.id || edge.target === selected.id);
  const incomingIdsArray = touchingEdges
    .filter((edge) => edge.type === "CALLS" && edge.target === selected.id)
    .map((edge) => edge.source);
  const outgoingIdsArray = touchingEdges
    .filter((edge) => edge.source === selected.id)
    .map((edge) => edge.target);
  const allowedIds = new Set<string>([selected.id, ...incomingIdsArray, ...outgoingIdsArray]);
  if (ownerClass) {
    allowedIds.add(ownerClass.id);
  }
  const edges = detail.graphEdges.filter((edge) => {
    if (ownerClass && edge.type === "DECLARES") {
      return edge.source === ownerClass.id && edge.target === selected.id;
    }
    return touchingEdges.some((candidate) => candidate.id === edge.id);
  });

  return {
    nodes: detail.graphNodes.filter((node) => allowedIds.has(node.id)),
    edges,
    scope: `${selected.label}() · ${incomingIdsArray.length} 个调用方 · ${outgoingIdsArray.length} 个下游`,
    mode: "method",
    centerId: selected.id,
    incomingIds: new Set(incomingIdsArray),
    incomingIdsArray,
    outgoingIdsArray,
  };
}

function emptyFocusedGraph(scope: string): FocusedGraph {
  return {
    nodes: [],
    edges: [],
    scope,
    mode: "class",
    incomingIds: new Set<string>(),
    incomingIdsArray: [],
    outgoingIdsArray: [],
  };
}

function singleNodeGraph(node: GraphNode): FocusedGraph {
  return {
    nodes: [node],
    edges: [],
    scope: node.label,
    mode: node.type,
    centerId: node.id,
    incomingIds: new Set<string>(),
    incomingIdsArray: [],
    outgoingIdsArray: [],
  };
}

function buildBusinessTraces(detail: ProjectDetail): BusinessTrace[] {
  const nodesById = new Map(detail.graphNodes.map((node) => [node.id, node]));
  const callEdges = detail.graphEdges.filter((edge) => edge.type === "CALLS");
  const outgoing = new Map<string, string[]>();
  const incomingCount = new Map<string, number>();
  for (const edge of callEdges) {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
    incomingCount.set(edge.target, (incomingCount.get(edge.target) ?? 0) + 1);
  }

  const entryNodes = detail.graphNodes
    .filter((node) => node.type === "method")
    .filter((node) => node.label === "main" || node.label === "run")
    .sort((left, right) => entryRank(left) - entryRank(right) || left.label.localeCompare(right.label))
    .slice(0, 8);

  const traces: BusinessTrace[] = [];
  for (const entry of entryNodes) {
    collectTracePaths(entry.id, outgoing, nodesById, 7, 3).forEach((path, index) => {
      const trimmedPath = trimAccessorTail(detail, path);
      if (trimmedPath.length < 2) {
        return;
      }
      const readable = trimmedPath.map((nodeId) => nodesById.get(nodeId)?.label ?? "unknown");
      const owner = ownerClassName(detail, entry.id);
      const terminal = readable[readable.length - 1];
      traces.push({
        id: `${entry.id}::trace-${index}`,
        title: `${owner}.${entry.label} -> ${terminal}`,
        summary: `从 ${owner}.${entry.label} 进入，经过 ${trimmedPath.length - 1} 次调用到达 ${terminal}。`,
        nodeIds: trimmedPath,
      });
    });
  }

  const unique = new Map<string, BusinessTrace>();
  for (const trace of traces) {
    if (!unique.has(trace.title)) {
      unique.set(trace.title, trace);
    }
  }
  return [...unique.values()]
    .sort((left, right) => right.nodeIds.length - left.nodeIds.length)
    .slice(0, 12);
}

function collectTracePaths(
  startId: string,
  outgoing: Map<string, string[]>,
  nodesById: Map<string, GraphNode>,
  maxDepth: number,
  maxBranches: number,
): string[][] {
  const result: string[][] = [];
  const walk = (path: string[]) => {
    const current = path[path.length - 1];
    const nextIds = (outgoing.get(current) ?? [])
      .filter((nodeId) => nodesById.has(nodeId) && !path.includes(nodeId))
      .slice(0, maxBranches);
    if (path.length >= maxDepth || nextIds.length === 0) {
      result.push(path);
      return;
    }
    for (const nextId of nextIds) {
      walk([...path, nextId]);
    }
  };
  walk([startId]);
  return result;
}

function trimAccessorTail(detail: ProjectDetail, path: string[]) {
  const nextPath = [...path];
  while (nextPath.length > 2 && isAccessorLikeMethod(detail, nextPath[nextPath.length - 1])) {
    nextPath.pop();
  }
  return nextPath;
}

function isAccessorLikeMethod(detail: ProjectDetail, nodeId: string) {
  const method = methodById(detail, nodeId);
  if (!method) {
    return false;
  }
  const name = method.name.toLowerCase();
  const commonAccessors = new Set([
    "id",
    "role",
    "leaderid",
    "currentterm",
    "commitindex",
    "lastlogindex",
    "lastlogterm",
    "linkkey",
    "snapshot",
    "statesnapshot",
    "logsummary",
  ]);
  return method.calls.length === 0
    && method.returnType !== "void"
    && (commonAccessors.has(name) || name.startsWith("get") || name.startsWith("is"));
}

function methodById(detail: ProjectDetail, nodeId: string) {
  for (const classInfo of detail.classes) {
    const method = classInfo.methods.find((item) => item.id === nodeId);
    if (method) {
      return method;
    }
  }
  return undefined;
}

function readableError(message: string) {
  try {
    const parsed = JSON.parse(message) as { message?: string };
    return parsed.message ?? message;
  } catch {
    return message;
  }
}

function entryRank(node: GraphNode) {
  if (node.label === "main") {
    return 0;
  }
  if (node.label === "run") {
    return 1;
  }
  return 2;
}

function graphNodeSubtitle(detail: ProjectDetail, node: GraphNode) {
  const owner = ownerClassName(detail, node.id);
  const returnType = String(node.data.returnType ?? "方法");
  return owner ? `${owner} · ${returnType}` : returnType;
}

function localizedKind(kind: string) {
  switch (kind) {
    case "class":
      return "类";
    case "record":
      return "记录";
    case "enum":
      return "枚举";
    case "interface":
      return "接口";
    default:
      return kind;
  }
}

function ownerClassName(detail: ProjectDetail, methodId: string) {
  const owner = detail.classes.find((classInfo) => classInfo.methods.some((method) => method.id === methodId));
  return owner?.name ?? "";
}

function buildAiMethodExplanations(
  detail: ProjectDetail | null,
  selectedTrace: BusinessTrace | undefined,
  selectedNodeId: string | undefined,
  summary: string,
  aiGraph: AiCallGraphResponse | null,
) {
  const explanations = new Map<string, MethodExplanation>();
  if (!detail || !summary.trim()) {
    return explanations;
  }
  const nodeIds = selectedTrace?.nodeIds.length
    ? selectedTrace.nodeIds
    : selectedNodeId
      ? [selectedNodeId]
      : [];
  nodeIds.forEach((nodeId, index) => {
    const node = detail.graphNodes.find((candidate) => candidate.id === nodeId && candidate.type === "method");
    const method = node ? methodById(detail, node.id) : undefined;
    const owner = node ? ownerClassName(detail, node.id) : "";
    if (!node || !method) {
      return;
    }
    const aiGraphNode = aiGraph?.nodes.find((candidate) => candidate.id === node.id);
    const extracted = extractMethodExplanation(summary, owner, method);
    const fallback = compactExplanation([aiGraphNode?.explanation, describeStep(detail, node, index)].filter(Boolean).join(" "));
    const text = extracted || fallback;
    if (text) {
      explanations.set(node.id, {
        label: owner ? `${owner}.${method.name}` : method.name,
        text,
      });
    }
  });
  return explanations;
}

function extractMethodExplanation(summary: string, owner: string, method: MethodInfo) {
  const plain = summary
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/[`*_>#-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!plain) {
    return "";
  }
  const tokens = [
    owner ? `${owner}.${method.name}` : "",
    `${method.name}(`,
    method.name.length > 3 ? method.name : "",
  ].filter(Boolean).map((token) => token.toLowerCase());
  const chunks = plain
    .split(/(?<=[。！？.!?])\s*/)
    .map((chunk) => chunk.trim())
    .filter(Boolean);
  const matched = chunks.filter((chunk) => {
    const lower = chunk.toLowerCase();
    return tokens.some((token) => lower.includes(token));
  });
  return compactExplanation(matched.slice(0, 2).join(" "));
}

function compactExplanation(value: string) {
  const text = value.replace(/\s+/g, " ").trim();
  return text.length > 360 ? `${text.slice(0, 356)}...` : text;
}

function TraceExplorer({
  detail,
  traces,
  selectedTraceId,
  selectedNodeId,
  collapsed,
  onToggleCollapsed,
  onSelectNode,
  onSelectTrace,
}: {
  detail: ProjectDetail | null;
  traces: BusinessTrace[];
  selectedTraceId: string;
  selectedNodeId?: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onSelectNode: (nodeId: string, filePath: string, line: number) => void;
  onSelectTrace: (trace: BusinessTrace) => void;
}) {
  if (!detail) {
    return null;
  }
  const nodesById = new Map(detail.graphNodes.map((node) => [node.id, node]));
  const selectedTrace = traces.find((trace) => trace.id === selectedTraceId);
  if (collapsed) {
    return (
      <section className="reading-path collapsed">
        <button className="trace-collapse-rail" type="button" onClick={onToggleCollapsed} title="展开调用链和链路步骤">
          <PanelBottomOpen size={15} />
          <strong>调用链 / 链路步骤</strong>
          <span>{selectedTrace ? selectedTrace.title : "展开查看阅读路径"}</span>
        </button>
      </section>
    );
  }
  return (
    <section className="reading-path">
      <div className="trace-column">
        <div className="trace-header-row">
          <PanelTitle icon={<Route size={16} />} title="调用链" />
          <button type="button" onClick={onToggleCollapsed} title="折叠调用链和链路步骤">
            <PanelBottomClose size={15} />
          </button>
        </div>
        <div className="trace-list">
          {traces.map((trace) => (
            <button
              key={trace.id}
              className={trace.id === selectedTraceId ? "active" : ""}
              onClick={() => onSelectTrace(trace)}
            >
              <strong>{trace.title}</strong>
              <span>{trace.summary}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="trace-column">
        <div className="trace-header-row">
          <PanelTitle icon={<LocateFixed size={16} />} title="链路步骤" />
        </div>
        <div className="trace-steps">
          {(selectedTrace?.nodeIds ?? []).map((nodeId, index) => {
            const node = nodesById.get(nodeId);
            if (!node) {
              return null;
            }
            return (
              <button
                key={`${selectedTrace?.id}-${nodeId}-${index}`}
                className={nodeId === selectedNodeId ? "active" : ""}
                onClick={() => onSelectNode(node.id, node.filePath, node.line)}
              >
                <span className="step-index">{index + 1}</span>
                <strong>{ownerClassName(detail, node.id) ? `${ownerClassName(detail, node.id)}.${node.label}` : node.label}</strong>
                <span>{describeStep(detail, node, index)}</span>
              </button>
            );
          })}
          {!selectedTrace ? (
            detail.readingPath.slice(0, 6).map((step) => (
              <button
                key={step.id}
                className={step.targetNodeId === selectedNodeId ? "active" : ""}
                onClick={() => onSelectNode(step.targetNodeId, step.filePath, step.line)}
              >
                <span className="step-index">•</span>
                <strong>{step.title}</strong>
                <span>{step.description}</span>
              </button>
            ))
          ) : null}
        </div>
      </div>
    </section>
  );
}

function AiAssistantPanel({
  question,
  outputMode,
  summary,
  model,
  loading,
  error,
  context,
  contextModel,
  contextLoading,
  contextError,
  contextCopied,
  disabled,
  sourceLinks,
  onQuestionChange,
  onAsk,
  onGenerateContext,
  onCopyContext,
  onClearHistory,
  onOpenSource,
}: {
  question: string;
  outputMode: AiOutputMode;
  summary: string;
  model: string;
  loading: boolean;
  error: string;
  context: string;
  contextModel: string;
  contextLoading: boolean;
  contextError: string;
  contextCopied: boolean;
  disabled: boolean;
  sourceLinks: SourceLink[];
  onQuestionChange: (value: string) => void;
  onAsk: () => void;
  onGenerateContext: () => void;
  onCopyContext: () => void;
  onClearHistory: () => void;
  onOpenSource: (link: SourceLink) => void;
}) {
  const activeModel = outputMode === "context" ? contextModel : model;
  const activeError = outputMode === "context" ? contextError : error;
  const hasHistory = Boolean(summary || model || error || context || contextModel || contextError);
  return (
    <div className="ai-panel">
      <div className="ai-box">
        <textarea
          value={question}
          onChange={(event) => onQuestionChange(event.target.value)}
          placeholder="问这条链路，或描述你准备让代码模型完成的改动目标"
          disabled={disabled || loading || contextLoading}
        />
        <div className="ai-actions">
          {activeModel ? <span>模型：{activeModel}</span> : <span>选择调用链后生成解释或编码 Context</span>}
        </div>
        <div className="ai-command-row">
          <button
            className={outputMode === "explain" ? "active" : ""}
            type="button"
            onClick={onAsk}
            disabled={disabled || loading || contextLoading}
            title="解释当前链路"
          >
            {loading ? <RefreshCw size={14} /> : <Send size={14} />}
            <span>{loading ? "生成中" : summary ? "重新解释" : "解释"}</span>
          </button>
          <button
            className={outputMode === "context" ? "active" : ""}
            type="button"
            onClick={onGenerateContext}
            disabled={disabled || loading || contextLoading}
            title="生成可粘贴给代码模型的链路 Context"
          >
            {contextLoading ? <RefreshCw size={14} /> : <Clipboard size={14} />}
            <span>{contextLoading ? "生成中" : "Context"}</span>
          </button>
          <button
            type="button"
            onClick={onCopyContext}
            disabled={!context || contextLoading}
            title="复制 Context"
          >
            {contextCopied ? <ClipboardCheck size={14} /> : <Clipboard size={14} />}
            <span>{contextCopied ? "已复制" : "复制"}</span>
          </button>
          <button
            className="clear-history"
            type="button"
            onClick={onClearHistory}
            disabled={disabled || loading || contextLoading || !hasHistory}
            title="清空 AI 输出历史"
          >
            <Trash2 size={14} />
            <span>清空</span>
          </button>
        </div>
        {sourceLinks.length ? (
          <div className="ai-source-links" aria-label="相关源码">
            <strong>相关源码</strong>
            <div>
              {sourceLinks.slice(0, 6).map((link) => (
                <button key={link.id} type="button" onClick={() => onOpenSource(link)} title="打开并定位源码">
                  <span>{link.label}</span>
                  <small>{link.subtitle}</small>
                </button>
              ))}
            </div>
          </div>
        ) : null}
        {activeError ? <div className="ai-error">{activeError}</div> : null}
        <div className="ai-answer">
          {outputMode === "context" && context ? (
            <ReactMarkdown>{context}</ReactMarkdown>
          ) : outputMode === "context" && contextLoading ? (
            <p>正在生成可复用的编码 Context，稍后可以一键复制给代码模型。</p>
          ) : outputMode === "context" ? (
            <p>选择调用链后生成 Context。上方输入框可以补充这次准备修改的目标，输出会更适合直接放进代码模型的 prompt。</p>
          ) : summary ? (
            <ReactMarkdown>{summary}</ReactMarkdown>
          ) : loading ? (
            <p>正在生成解释，内容会实时显示在这里。</p>
          ) : (
            <p>选择调用链后生成解释；也可以输入一个具体问题。上方的相关源码可直接打开并定位到对应方法。</p>
          )}
        </div>
      </div>
    </div>
  );
}

function describeStep(detail: ProjectDetail, node: GraphNode, index: number) {
  const owner = detail.classes.find((classInfo) => classInfo.methods.some((method) => method.id === node.id));
  const method = owner?.methods.find((item) => item.id === node.id);
  if (!method) {
    return index === 0 ? "链路入口。" : "进入相关类型。";
  }
  const concepts = method.concepts.length ? method.concepts.slice(0, 3).join("、") : method.calls.slice(0, 3).join("、");
  if (index === 0) {
    return concepts ? `入口方法，先建立 ${concepts} 的上下文。` : "入口方法，负责拉起这条流程。";
  }
  return concepts ? `继续处理 ${concepts}。` : `${method.calls.length} 个下游调用，返回 ${method.returnType}。`;
}

function ReadingPath({
  detail,
  selectedNodeId,
  onSelect,
}: {
  detail: ProjectDetail | null;
  selectedNodeId?: string;
  onSelect: (nodeId: string, filePath: string, line: number) => void;
}) {
  if (!detail) {
    return null;
  }
  return (
    <section className="reading-path legacy-reading-path">
      <PanelTitle icon={<LocateFixed size={16} />} title="阅读路径" />
      <div className="reading-list">
        {detail.readingPath.map((step) => (
          <button
            key={step.id}
            className={step.targetNodeId === selectedNodeId ? "active" : ""}
            onClick={() => onSelect(step.targetNodeId, step.filePath, step.line)}
          >
            <strong>{step.title}</strong>
            <span>{step.description}</span>
          </button>
        ))}
      </div>
    </section>
  );
}

function SelectionSummary({ node, detail }: { node?: GraphNode; detail: ProjectDetail | null }) {
  if (!node || !detail) {
    return <div className="selection-summary">选择一个类、方法或图节点。</div>;
  }
  const classInfo = detail.classes.find((item) => item.id === node.id || item.methods.some((method) => method.id === node.id));
  const methodInfo: MethodInfo | undefined = classInfo?.methods.find((method) => method.id === node.id);
  const concepts = methodInfo?.concepts.length ? methodInfo.concepts : classInfo?.concepts ?? [];
  return (
    <div className="selection-summary">
      <strong>{node.label}</strong>
      <span>{methodInfo ? methodInfo.signature : classInfo?.qualifiedName}</span>
      <Concepts concepts={concepts.slice(0, 6)} />
    </div>
  );
}

function SourceViewer({
  source,
  highlightLine,
  selectedNode,
  detail,
  methodExplanations,
}: {
  source: SourceResponse | null;
  highlightLine?: number;
  selectedNode?: GraphNode;
  detail: ProjectDetail | null;
  methodExplanations: Map<string, MethodExplanation>;
}) {
  const lines = useMemo(() => source?.content.split("\n") ?? [], [source]);
  const activeRange = useMemo(() => {
    if (!selectedNode || !detail) {
      return null;
    }
    const owner = detail.classes.find((classInfo) => classInfo.id === selectedNode.id || classInfo.methods.some((method) => method.id === selectedNode.id));
    if (!owner) {
      return null;
    }
    const method = owner.methods.find((item) => item.id === selectedNode.id);
    return method
      ? { begin: method.beginLine, end: method.endLine }
      : { begin: owner.beginLine, end: owner.endLine };
  }, [detail, selectedNode]);
  const lineExplanations = useMemo(() => {
    const annotations = new Map<number, MethodExplanation & { entryLine: number }>();
    if (!source || !detail || !methodExplanations.size) {
      return annotations;
    }
    detail.classes
      .filter((classInfo) => classInfo.filePath === source.path)
      .forEach((classInfo) => {
        classInfo.methods.forEach((method) => {
          const explanation = methodExplanations.get(method.id);
          if (!explanation) {
            return;
          }
          for (let lineNo = method.beginLine; lineNo <= method.endLine; lineNo += 1) {
            annotations.set(lineNo, { ...explanation, entryLine: method.beginLine });
          }
        });
      });
    return annotations;
  }, [detail, methodExplanations, source]);
  if (!source) {
    return <div className="source-empty">还没有选择源码文件。</div>;
  }
  return (
    <div className="source-view">
      <div className="source-path">{source.path}</div>
      <pre>
        {lines.map((line, index) => {
          const lineNo = index + 1;
          const inRange = activeRange ? lineNo >= activeRange.begin && lineNo <= activeRange.end : false;
          const explanation = lineExplanations.get(lineNo);
          return (
            <div
              key={lineNo}
              className={`code-line ${inRange ? "in-range" : ""} ${lineNo === highlightLine ? "highlight" : ""} ${explanation ? "has-ai-explanation" : ""}`}
            >
              <span className="line-no">{lineNo}</span>
              <code>{line || " "}</code>
              {explanation ? (
                <span className={`ai-source-note ${lineNo === explanation.entryLine ? "entry" : ""}`}>
                  <span className="ai-source-badge">AI</span>
                  <span className="source-tooltip" role="tooltip">
                    <strong>{explanation.label}</strong>
                    <span>{explanation.text}</span>
                  </span>
                </span>
              ) : null}
            </div>
          );
        })}
      </pre>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

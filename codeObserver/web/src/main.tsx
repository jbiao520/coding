import React, { useEffect, useMemo, useRef, useState } from "react";
import ReactDOM from "react-dom/client";
import Alert from "antd/es/alert";
import Button from "antd/es/button";
import ConfigProvider from "antd/es/config-provider";
import Input from "antd/es/input";
import Segmented from "antd/es/segmented";
import Tag from "antd/es/tag";
import "antd/dist/reset.css";
import ReactMarkdown from "react-markdown";
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  BookOpen,
  Braces,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Clipboard,
  ClipboardCheck,
  ExternalLink,
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
  Package as PackageIcon,
  Pin,
  PinOff,
  RefreshCw,
  Route,
  Search,
  Send,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { loadAiCallGraph, loadAiRecommendedFlows, loadProject, loadSource, openSourceInIde, scanWorkspace, streamAiCodingContext, streamAiSummary } from "./api";
import type {
  AiCallGraphResponse,
  ClassInfo,
  CodeReference,
  FlowInfo,
  FlowStep,
  GraphEdge,
  GraphNode,
  MethodInfo,
  PinnedSourceSnippet,
  ProjectDetail,
  ProjectSummary,
  SourceResponse,
} from "./types";
import "./styles.css";

const { TextArea } = Input;

type Selection = {
  nodeId?: string;
  filePath?: string;
  line?: number;
};

type SourceNavigationEntry = Required<Selection> & {
  label: string;
};

type BusinessTrace = {
  id: string;
  title: string;
  summary: string;
  nodeIds: string[];
  sourceKind?: string;
  confidence?: number;
  tags?: string[];
  steps?: FlowStep[];
  isBackendFlow?: boolean;
};

type PackageGroup = {
  name: string;
  classes: ClassInfo[];
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

type SourceLineExplanation = MethodExplanation & {
  entryLine: number;
  kind: "entry" | "body" | "call";
  triggerText?: string;
};

type SourceLineJump = {
  triggerText: string;
  label: string;
  nodeId: string;
  filePath: string;
  line: number;
};

type ReferenceJump = {
  label: string;
  nodeId: string;
  filePath: string;
  line: number;
};

type SourceLineReference = {
  reference: CodeReference;
  jump?: ReferenceJump;
};

type AiCacheEntry = {
  outputMode: AiOutputMode;
  question: string;
  summary: string;
  model: string;
  context: string;
  contextModel: string;
  updatedAt: number;
};

const AI_CACHE_PREFIX = "codeObserver.ai.v1";
const PROJECTS_COLLAPSED_STORAGE_KEY = "codeObserver.projectsCollapsed.v1";
const TRACE_PANEL_COLLAPSED_STORAGE_KEY = "codeObserver.tracePanelCollapsed.v1";
const TRACE_PANEL_HEIGHT_STORAGE_KEY = "codeObserver.tracePanelHeight.v1";
const NAVIGATOR_WIDTH_STORAGE_KEY = "codeObserver.navigatorWidth.v1";
const TRACE_PANEL_HEIGHT_DEFAULT = 240;
const TRACE_PANEL_HEIGHT_MIN = 120;
const TRACE_PANEL_HEIGHT_MAX = 520;
const NAVIGATOR_WIDTH_DEFAULT = 330;
const NAVIGATOR_WIDTH_MIN = 240;
const NAVIGATOR_WIDTH_MAX = 560;
const aiMemoryCache = new Map<string, string>();

function App() {
  const [root, setRoot] = useState("");
  const [workspaceRoot, setWorkspaceRoot] = useState("");
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>("");
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [source, setSource] = useState<SourceResponse | null>(null);
  const [selection, setSelection] = useState<Selection>({});
  const [pinnedSnippets, setPinnedSnippets] = useState<PinnedSourceSnippet[]>([]);
  const [sourceHistory, setSourceHistory] = useState<SourceNavigationEntry[]>([]);
  const [sourceHistoryIndex, setSourceHistoryIndex] = useState(-1);
  const [selectedTraceId, setSelectedTraceId] = useState("");
  const [aiRecommendedFlows, setAiRecommendedFlows] = useState<BusinessTrace[]>([]);
  const [aiFlowLoading, setAiFlowLoading] = useState(false);
  const [aiFlowError, setAiFlowError] = useState("");
  const [workspaceDrawerOpen, setWorkspaceDrawerOpen] = useState(false);
  const [activeDrawerTab, setActiveDrawerTab] = useState<DrawerTab>("source");
  const [tracePanelCollapsed, setTracePanelCollapsed] = useState(() => readBooleanPreference(TRACE_PANEL_COLLAPSED_STORAGE_KEY));
  const [tracePanelHeight, setTracePanelHeight] = useState(() =>
    readNumberPreference(TRACE_PANEL_HEIGHT_STORAGE_KEY, TRACE_PANEL_HEIGHT_DEFAULT, TRACE_PANEL_HEIGHT_MIN, TRACE_PANEL_HEIGHT_MAX),
  );
  const [projectsCollapsed, setProjectsCollapsed] = useState(() => readBooleanPreference(PROJECTS_COLLAPSED_STORAGE_KEY));
  const [navigatorWidth, setNavigatorWidth] = useState(() =>
    readNumberPreference(NAVIGATOR_WIDTH_STORAGE_KEY, NAVIGATOR_WIDTH_DEFAULT, NAVIGATOR_WIDTH_MIN, NAVIGATOR_WIDTH_MAX),
  );
  const [query, setQuery] = useState("");
  const [expandedPackages, setExpandedPackages] = useState<Set<string>>(new Set());
  const [expandedClasses, setExpandedClasses] = useState<Set<string>>(new Set());
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
  const [openIdeStatus, setOpenIdeStatus] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void handleScan("");
  }, []);

  useEffect(() => {
    writeBooleanPreference(PROJECTS_COLLAPSED_STORAGE_KEY, projectsCollapsed);
  }, [projectsCollapsed]);

  useEffect(() => {
    writeBooleanPreference(TRACE_PANEL_COLLAPSED_STORAGE_KEY, tracePanelCollapsed);
  }, [tracePanelCollapsed]);

  useEffect(() => {
    writeNumberPreference(TRACE_PANEL_HEIGHT_STORAGE_KEY, tracePanelHeight);
  }, [tracePanelHeight]);

  useEffect(() => {
    writeNumberPreference(NAVIGATOR_WIDTH_STORAGE_KEY, navigatorWidth);
  }, [navigatorWidth]);

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
        setPinnedSnippets([]);
        setExpandedPackages(new Set());
        setExpandedClasses(new Set());
        setSourceHistory([]);
        setSourceHistoryIndex(-1);
        setGraphMode("code");
        setAiGraph(null);
        setAiGraphError("");
        setAiRecommendedFlows([]);
        setAiFlowLoading(false);
        setAiFlowError("");
        setOpenIdeStatus("");
        const traces = buildRecommendedFlows(nextDetail);
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

  const normalizedQuery = useMemo(() => query.trim().toLowerCase(), [query]);

  const filteredClasses = useMemo(() => {
    if (!detail) {
      return [];
    }
    if (!normalizedQuery) {
      return detail.classes;
    }
    return detail.classes.filter((classInfo) => {
      return classMatchesQuery(classInfo, normalizedQuery) || packageMatchesQuery(classInfo, normalizedQuery);
    });
  }, [detail, normalizedQuery]);

  const packageGroups = useMemo<PackageGroup[]>(() => {
    const groups = new Map<string, ClassInfo[]>();
    filteredClasses.forEach((classInfo) => {
      const packageName = classInfo.packageName || "(default package)";
      groups.set(packageName, [...(groups.get(packageName) ?? []), classInfo]);
    });
    return [...groups.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([name, classes]) => ({
        name,
        classes: [...classes].sort((left, right) => left.name.localeCompare(right.name)),
      }));
  }, [filteredClasses]);

  const searchExpandedPackages = useMemo(() => {
    if (!normalizedQuery) {
      return expandedPackages;
    }
    const next = new Set(expandedPackages);
    packageGroups.forEach((group) => next.add(group.name));
    return next;
  }, [expandedPackages, normalizedQuery, packageGroups]);

  const searchExpandedClasses = useMemo(() => {
    if (!normalizedQuery) {
      return expandedClasses;
    }
    const next = new Set(expandedClasses);
    filteredClasses.forEach((classInfo) => {
      if (classBodyMatchesQuery(classInfo, normalizedQuery)) {
        next.add(classInfo.id);
      }
    });
    return next;
  }, [expandedClasses, filteredClasses, normalizedQuery]);

  const businessTraces = useMemo(
    () => (aiRecommendedFlows.length ? aiRecommendedFlows : detail ? buildRecommendedFlows(detail) : []),
    [aiRecommendedFlows, detail],
  );
  const selectedTrace = useMemo(
    () => businessTraces.find((trace) => trace.id === selectedTraceId),
    [businessTraces, selectedTraceId],
  );

  const aiCacheKey = useMemo(() => {
    if (!detail || !selectedProjectId) {
      return "";
    }
    return buildAiCacheKey({
      root: workspaceRoot || root,
      projectId: selectedProjectId,
      traceId: selectedTrace?.id,
      nodeId: selectedTrace ? undefined : selection.nodeId,
      question: aiQuestion,
      pinsKey: pinnedSnippets.map((snippet) => snippet.id).join("|"),
    });
  }, [aiQuestion, detail, pinnedSnippets, root, selectedProjectId, selectedTrace, selection.nodeId, workspaceRoot]);

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

  useEffect(() => {
    if (!aiCacheKey || aiLoading || aiContextLoading) {
      return;
    }
    const cached = readAiCache(aiCacheKey);
    if (!cached) {
      setAiOutputMode("explain");
      setAiSummary("");
      setAiModel("");
      setAiError("");
      setAiContext("");
      setAiContextModel("");
      setAiContextError("");
      setAiContextCopied(false);
      return;
    }
    setAiOutputMode(cached.outputMode);
    setAiSummary(cached.summary);
    setAiModel(cached.model);
    setAiError("");
    setAiContext(cached.context);
    setAiContextModel(cached.contextModel);
    setAiContextError("");
    setAiContextCopied(false);
  }, [aiCacheKey]);

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

  function togglePackage(packageName: string) {
    setExpandedPackages((current) => {
      const next = new Set(current);
      if (next.has(packageName)) {
        next.delete(packageName);
      } else {
        next.add(packageName);
      }
      return next;
    });
  }

  function toggleClass(classId: string) {
    setExpandedClasses((current) => {
      const next = new Set(current);
      if (next.has(classId)) {
        next.delete(classId);
      } else {
        next.add(classId);
      }
      return next;
    });
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

  function currentSourceHistoryEntry(): SourceNavigationEntry | null {
    if (!selection.nodeId || !selection.filePath || !selection.line) {
      return null;
    }
    return {
      nodeId: selection.nodeId,
      filePath: selection.filePath,
      line: selection.line,
      label: selectedNode?.label ?? "当前位置",
    };
  }

  function sameSourceEntry(left: SourceNavigationEntry, right: SourceNavigationEntry) {
    return left.nodeId === right.nodeId && left.filePath === right.filePath && left.line === right.line;
  }

  function jumpToSource(entry: SourceNavigationEntry) {
    const currentEntry = currentSourceHistoryEntry();
    setSourceHistory((history) => {
      const base = sourceHistoryIndex >= 0
        ? history.slice(0, sourceHistoryIndex + 1)
        : currentEntry
          ? [currentEntry]
          : [];
      const last = base[base.length - 1];
      const nextHistory = last && sameSourceEntry(last, entry)
        ? base
        : [...base, entry].slice(-80);
      setSourceHistoryIndex(nextHistory.length - 1);
      return nextHistory;
    });
    selectNode(entry.nodeId, entry.filePath, entry.line, {
      preserveAi: true,
      preserveTrace: true,
      preserveAiGraph: graphMode === "ai",
    });
    openSourceDrawer();
  }

  function moveSourceHistory(offset: -1 | 1) {
    const nextIndex = sourceHistoryIndex + offset;
    const entry = sourceHistory[nextIndex];
    if (!entry) {
      return;
    }
    setSourceHistoryIndex(nextIndex);
    selectNode(entry.nodeId, entry.filePath, entry.line, {
      preserveAi: true,
      preserveTrace: true,
      preserveAiGraph: graphMode === "ai",
    });
    openSourceDrawer();
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
    const requestedQuestion = question.trim();
    const cacheKey = buildAiCacheKey({
      root: workspaceRoot || root,
      projectId: selectedProjectId,
      traceId: selectedTrace?.id,
      nodeId: selectedTrace ? undefined : selection.nodeId,
      question: requestedQuestion,
      pinsKey: pinnedSnippets.map((snippet) => snippet.id).join("|"),
    });
    let content = "";
    setAiOutputMode("explain");
    setAiLoading(true);
    setAiError("");
    setAiSummary("");
    setAiModel("");
    streamAiSummary({
      projectId: selectedProjectId,
      root: workspaceRoot || root,
      selectedNodeId: selection.nodeId,
      flowId: selectedTrace?.isBackendFlow ? selectedTrace.id : undefined,
      trace: selectedTrace,
      question: requestedQuestion,
    }, (chunk) => {
      content += chunk;
      setAiSummary((current) => current + chunk);
    })
      .then((response) => {
        setAiModel(response.model);
        if (cacheKey) {
          writeAiCache(cacheKey, {
            outputMode: "explain",
            question: requestedQuestion,
            summary: content.trim(),
            model: response.model,
            context: aiContext,
            contextModel: aiContextModel,
            updatedAt: Date.now(),
          });
        }
      })
      .catch((err: Error) => setAiError(readableError(err.message)))
      .finally(() => setAiLoading(false));
  }

  function handleAiCodingContext(task = aiQuestion) {
    if (!detail || !selectedProjectId) {
      return;
    }
    const requestedTask = task.trim();
    const cacheKey = buildAiCacheKey({
      root: workspaceRoot || root,
      projectId: selectedProjectId,
      traceId: selectedTrace?.id,
      nodeId: selectedTrace ? undefined : selection.nodeId,
      question: requestedTask,
      pinsKey: pinnedSnippets.map((snippet) => snippet.id).join("|"),
    });
    let content = "";
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
      flowId: selectedTrace?.isBackendFlow ? selectedTrace.id : undefined,
      trace: selectedTrace,
      task: requestedTask,
      pinnedSnippets,
    }, (chunk) => {
      content += chunk;
      setAiContext((current) => current + chunk);
    })
      .then((response) => {
        setAiContextModel(response.model);
        if (cacheKey) {
          writeAiCache(cacheKey, {
            outputMode: "context",
            question: requestedTask,
            summary: aiSummary,
            model: aiModel,
            context: content.trim(),
            contextModel: response.model,
            updatedAt: Date.now(),
          });
        }
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
      flowId: selectedTrace?.isBackendFlow ? selectedTrace.id : undefined,
      trace: selectedTrace,
    })
      .then((nextGraph) => {
        setAiGraph(nextGraph);
      })
      .catch((err: Error) => setAiGraphError(readableError(err.message)))
      .finally(() => setAiGraphLoading(false));
  }

  function handleAiRecommendedFlows() {
    if (!detail || !selectedProjectId) {
      return;
    }
    setAiFlowLoading(true);
    setAiFlowError("");
    loadAiRecommendedFlows({
      projectId: selectedProjectId,
      root: workspaceRoot || root,
      selectedNodeId: selection.nodeId,
      instruction: aiQuestion,
    })
      .then((response) => {
        const nextFlows = response.flows.map(flowInfoToTrace);
        setAiRecommendedFlows(nextFlows);
        setSelectedTraceId(nextFlows[0]?.id ?? "");
        setGraphMode("code");
        setAiGraph(null);
        setAiGraphError("");
        const firstNodeId = nextFlows[0]?.nodeIds[0];
        const firstNode = firstNodeId ? detail.graphNodes.find((node) => node.id === firstNodeId) : undefined;
        if (firstNode) {
          setSelection({ nodeId: firstNode.id, filePath: firstNode.filePath, line: firstNode.line });
        }
      })
      .catch((err: Error) => setAiFlowError(readableError(err.message)))
      .finally(() => setAiFlowLoading(false));
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
    if (aiCacheKey) {
      removeAiCache(aiCacheKey);
    }
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

  function addPinnedSnippet(snippet: PinnedSourceSnippet) {
    setPinnedSnippets((current) => {
      const withoutDuplicate = current.filter((item) => item.id !== snippet.id);
      return [snippet, ...withoutDuplicate].slice(0, 24);
    });
  }

  function removePinnedSnippet(id: string) {
    setPinnedSnippets((current) => current.filter((snippet) => snippet.id !== id));
  }

  function clearPinnedSnippets() {
    setPinnedSnippets([]);
  }

  function pinCurrentSelection() {
    if (!detail || !source) {
      return;
    }
    const range = selection.nodeId ? sourceRangeForNode(detail, selection.nodeId) : null;
    const fallbackLine = selection.line ?? 1;
    const snippet = buildPinnedSnippetFromContent({
      label: range?.label ?? `Line ${fallbackLine}`,
      filePath: range?.filePath ?? source.path,
      beginLine: range?.beginLine ?? fallbackLine,
      endLine: range?.endLine ?? fallbackLine,
      nodeId: selection.nodeId,
      sourcePath: source.path,
      sourceContent: source.content,
    });
    if (snippet) {
      addPinnedSnippet(snippet);
    }
  }

  async function pinSelectedTraceNodes() {
    if (!detail || !selectedTrace?.nodeIds.length) {
      return;
    }
    const snippets: PinnedSourceSnippet[] = [];
    for (const nodeId of selectedTrace.nodeIds.slice(0, 10)) {
      const range = sourceRangeForNode(detail, nodeId);
      if (!range) {
        continue;
      }
      const sourceResponse = source?.path === range.filePath ? source : await loadSource(range.filePath);
      const snippet = buildPinnedSnippetFromContent({
        label: range.label,
        filePath: range.filePath,
        beginLine: range.beginLine,
        endLine: range.endLine,
        nodeId,
        sourcePath: sourceResponse.path,
        sourceContent: sourceResponse.content,
      });
      if (snippet) {
        snippets.push(snippet);
      }
    }
    if (snippets.length) {
      setPinnedSnippets((current) => {
        const byId = new Map<string, PinnedSourceSnippet>();
        [...snippets, ...current].forEach((snippet) => byId.set(snippet.id, snippet));
        return [...byId.values()].slice(0, 24);
      });
    }
  }

  async function handleOpenInIde() {
    if (!source) {
      return;
    }
    setOpenIdeStatus("正在打开 IDE...");
    try {
      const response = await openSourceInIde(source.path, selection.line ?? 1);
      setOpenIdeStatus(response.ok ? "已发送到 IDE" : readableError(response.message));
    } catch (err) {
      setOpenIdeStatus(err instanceof Error ? readableError(err.message) : "打开 IDE 失败");
    }
  }

  function resizeNavigator(nextWidth: number) {
    setNavigatorWidth(clampNumber(nextWidth, NAVIGATOR_WIDTH_MIN, NAVIGATOR_WIDTH_MAX));
  }

  function resizeTracePanel(nextHeight: number) {
    setTracePanelHeight(clampNumber(nextHeight, TRACE_PANEL_HEIGHT_MIN, TRACE_PANEL_HEIGHT_MAX));
  }

  function handleNavigatorResizeMouseDown(event: React.MouseEvent<HTMLDivElement>) {
    if (event.button !== 0) {
      return;
    }
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = navigatorWidth;
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;

    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";

    function handleMouseMove(moveEvent: MouseEvent) {
      resizeNavigator(startWidth + moveEvent.clientX - startX);
    }

    function handleMouseUp() {
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener("mousemove", handleMouseMove);
    }

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp, { once: true });
  }

  function handleTraceResizeMouseDown(event: React.MouseEvent<HTMLDivElement>) {
    if (event.button !== 0 || tracePanelCollapsed) {
      return;
    }
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = tracePanelHeight;
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;

    document.body.style.cursor = "row-resize";
    document.body.style.userSelect = "none";

    function handleMouseMove(moveEvent: MouseEvent) {
      resizeTracePanel(startHeight + startY - moveEvent.clientY);
    }

    function handleMouseUp() {
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener("mousemove", handleMouseMove);
    }

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp, { once: true });
  }

  function handleNavigatorResizeKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      resizeNavigator(navigatorWidth - 16);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      resizeNavigator(navigatorWidth + 16);
    } else if (event.key === "Home") {
      event.preventDefault();
      resizeNavigator(NAVIGATOR_WIDTH_MIN);
    } else if (event.key === "End") {
      event.preventDefault();
      resizeNavigator(NAVIGATOR_WIDTH_MAX);
    }
  }

  function handleTraceResizeKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "ArrowUp") {
      event.preventDefault();
      resizeTracePanel(tracePanelHeight + 16);
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      resizeTracePanel(tracePanelHeight - 16);
    } else if (event.key === "Home") {
      event.preventDefault();
      resizeTracePanel(TRACE_PANEL_HEIGHT_MIN);
    } else if (event.key === "End") {
      event.preventDefault();
      resizeTracePanel(TRACE_PANEL_HEIGHT_MAX);
    }
  }

  const workspaceGridStyle = {
    "--navigator-width": `${navigatorWidth}px`,
    "--trace-panel-height": `${tracePanelHeight}px`,
  } as React.CSSProperties;

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
          <Input
            aria-label="源码根目录"
            className="root-input"
            variant="borderless"
            prefix={<FolderSearch size={17} />}
            value={root}
            onChange={(event) => setRoot(event.target.value)}
            placeholder="/path/to/workspace"
          />
          <Button type="primary" htmlType="submit" title="扫描目录" icon={<RefreshCw size={16} />}>
            扫描
          </Button>
        </form>
      </header>

      {error ? <Alert className="error-bar" type="error" message={error} banner /> : null}

      <section
        className={[
          "workspace-grid",
          projectsCollapsed ? "projects-collapsed" : "",
          workspaceDrawerOpen ? "drawer-open" : "",
        ].join(" ")}
        style={workspaceGridStyle}
      >
        <aside className="projects-panel">
          <div className="panel-header">
            {!projectsCollapsed ? <PanelTitle icon={<BookOpen size={16} />} title="项目" /> : null}
            <Button
              className="panel-collapse-button"
              htmlType="button"
              aria-label={projectsCollapsed ? "展开项目" : "折叠项目"}
              onClick={() => setProjectsCollapsed((collapsed) => !collapsed)}
              title={projectsCollapsed ? "展开项目" : "折叠项目"}
              icon={projectsCollapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
            />
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
            <Input
              className="search-input"
              size="small"
              variant="borderless"
              prefix={<Search size={15} />}
              allowClear
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索类、方法、概念"
            />
          </div>
          <div className="class-tree">
            {packageGroups.map((group) => (
              <PackageTree
                key={group.name}
                group={group}
                selectedNodeId={selection.nodeId}
                expandedPackages={searchExpandedPackages}
                expandedClasses={searchExpandedClasses}
                onTogglePackage={togglePackage}
                onToggleClass={toggleClass}
                onSelect={selectNode}
              />
            ))}
          </div>
          <div
            className="navigator-resize-handle"
            role="separator"
            aria-label="调整结构面板宽度"
            aria-orientation="vertical"
            aria-valuemin={NAVIGATOR_WIDTH_MIN}
            aria-valuemax={NAVIGATOR_WIDTH_MAX}
            aria-valuenow={navigatorWidth}
            tabIndex={0}
            title="拖拽调整结构面板宽度"
            onMouseDown={handleNavigatorResizeMouseDown}
            onKeyDown={handleNavigatorResizeKeyDown}
          />
        </aside>

        <section className={`center-panel ${tracePanelCollapsed ? "trace-collapsed" : ""}`}>
          <div className="graph-header">
            <div>
              <PanelTitle icon={<Route size={16} />} title="调用图" />
              <p>
                {detail
                  ? graphMode === "ai"
                    ? `${detail.summary.name} · ${aiGraph?.title ?? "AI 正在精简链路"}`
                    : `${detail.summary.name} · ${selectedTrace ? "推荐 Flow" : "聚焦当前选中的类或方法"}`
                  : "等待扫描"}
              </p>
            </div>
            <div className="graph-actions">
              <Button
                className={`ai-graph-trigger ${graphMode === "ai" ? "active" : ""}`}
                onClick={handleAiCallGraph}
                disabled={!detail || aiGraphLoading}
                title={graphMode === "ai" && aiGraph ? "切回源码生成的调用图" : "AI 生成解释版调用图"}
                icon={<Sparkles size={15} />}
              >
                {aiGraphLoading ? "生成中" : graphMode === "ai" && aiGraph ? "源码图" : "AI 生成图"}
              </Button>
              <Button
                className={`ai-summary-trigger ${aiTabActive && aiOutputMode === "explain" ? "active" : ""}`}
                onClick={() => openAiDrawer("")}
                disabled={!detail}
                title={aiTabActive && aiOutputMode === "explain" ? "AI 解释已打开" : "AI 解释当前链路"}
                icon={<Sparkles size={15} />}
              >
                {aiLoading ? "生成中" : "AI 解释"}
              </Button>
              <Button
                className={`ai-context-trigger ${aiTabActive && aiOutputMode === "context" ? "active" : ""}`}
                onClick={() => openContextDrawer()}
                disabled={!detail}
                title={aiTabActive && aiOutputMode === "context" ? "Context 已打开" : "生成可粘贴给代码模型的链路 Context"}
                icon={<Clipboard size={15} />}
              >
                {aiContextLoading ? "生成中" : "Context"}
              </Button>
              <Button
                className={`source-toggle ${sourceTabActive ? "active" : ""}`}
                onClick={toggleSourceDrawer}
                title={sourceTabActive ? "关闭源码抽屉" : "显示源码"}
                icon={sourceTabActive ? <PanelRightClose size={15} /> : <PanelRightOpen size={15} />}
              >
                源码
              </Button>
              <Tag className="status-pill" color={loading ? "processing" : "default"}>{loading ? "加载中" : "就绪"}</Tag>
            </div>
          </div>
          <ReadingGuide
            detail={detail}
            selectedTrace={selectedTrace}
            loading={loading}
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
              graphMode === "ai" || selectedTrace ? { preserveAi: true, preserveTrace: true, preserveAiGraph: graphMode === "ai" } : {},
            )}
          />
          <div
            className="trace-resize-handle"
            role="separator"
            aria-label="调整推荐 Flow 面板高度"
            aria-orientation="horizontal"
            aria-valuemin={TRACE_PANEL_HEIGHT_MIN}
            aria-valuemax={TRACE_PANEL_HEIGHT_MAX}
            aria-valuenow={tracePanelHeight}
            tabIndex={tracePanelCollapsed ? -1 : 0}
            title="拖拽调整推荐 Flow 面板高度"
            onMouseDown={handleTraceResizeMouseDown}
            onKeyDown={handleTraceResizeKeyDown}
          />
          <TraceExplorer
            detail={detail}
            traces={businessTraces}
            selectedTraceId={selectedTraceId}
            selectedNodeId={selection.nodeId}
            collapsed={tracePanelCollapsed}
            aiFlowLoading={aiFlowLoading}
            aiFlowError={aiFlowError}
            onToggleCollapsed={() => setTracePanelCollapsed((collapsed) => !collapsed)}
            onGenerateAiFlows={handleAiRecommendedFlows}
            onSelectNode={(nodeId, filePath, line) => {
              selectNode(nodeId, filePath, line, { preserveAi: true, preserveTrace: true, preserveAiGraph: graphMode === "ai" });
              openSourceDrawer();
            }}
            onSelectTrace={selectTrace}
          />
        </section>

        {workspaceDrawerOpen ? (
          <button
            className="drawer-backdrop"
            type="button"
            aria-label="关闭源码抽屉"
            onClick={() => setWorkspaceDrawerOpen(false)}
          />
        ) : null}

        <aside className={`workspace-drawer side-drawer ${workspaceDrawerOpen ? "open" : ""}`} aria-hidden={!workspaceDrawerOpen}>
          <div className="drawer-header">
            <Segmented<DrawerTab>
              className="drawer-tabs"
              aria-label="源码和 AI 解释"
              value={activeDrawerTab}
              onChange={(value) => setActiveDrawerTab(value)}
              options={[
                {
                  value: "source",
                  label: (
                    <span className="segmented-label">
                      <FileCode2 size={15} />
                      源码
                    </span>
                  ),
                },
                {
                  value: "ai",
                  label: (
                    <span className="segmented-label">
                      <Sparkles size={15} />
                      AI 解释
                    </span>
                  ),
                },
              ]}
            />
            <Button
              className="drawer-close-button"
              htmlType="button"
              onClick={() => setWorkspaceDrawerOpen(false)}
              title="关闭抽屉"
              icon={<X size={16} />}
            />
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
                  pinnedCount={pinnedSnippets.length}
                  canPinTrace={Boolean(selectedTrace?.nodeIds.length)}
                  openIdeStatus={openIdeStatus}
                  canGoBack={sourceHistoryIndex > 0}
                  canGoForward={sourceHistoryIndex >= 0 && sourceHistoryIndex < sourceHistory.length - 1}
                  onBack={() => moveSourceHistory(-1)}
                  onForward={() => moveSourceHistory(1)}
                  onOpenInIde={handleOpenInIde}
                  onPinCurrent={pinCurrentSelection}
                  onPinTrace={pinSelectedTraceNodes}
                  onJump={(jump) => {
                    jumpToSource({
                      nodeId: jump.nodeId,
                      filePath: jump.filePath,
                      line: jump.line,
                      label: jump.label,
                    });
                  }}
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
                  pinnedSnippets={pinnedSnippets}
                  onQuestionChange={setAiQuestion}
                  onAsk={(questionOverride) => openAiDrawer(questionOverride ?? aiQuestion, true)}
                  onGenerateContext={() => openContextDrawer(true)}
                  onCopyContext={copyAiContext}
                  onClearHistory={clearAiHistory}
                  onRemovePin={removePinnedSnippet}
                  onClearPins={clearPinnedSnippets}
                  onOpenSource={(link) => {
                    jumpToSource({
                      nodeId: link.id,
                      filePath: link.filePath,
                      line: link.line,
                      label: link.label,
                    });
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
        <Tag key={concept} color="green">
          {concept}
        </Tag>
      ))}
    </div>
  );
}

function packageMatchesQuery(classInfo: ClassInfo, normalizedQuery: string) {
  const packageName = classInfo.packageName || "(default package)";
  return packageName.toLowerCase().includes(normalizedQuery);
}

function classBodyMatchesQuery(classInfo: ClassInfo, normalizedQuery: string) {
  return [
    classInfo.name,
    classInfo.concepts.join(" "),
    classInfo.methods.map((method) => method.name).join(" "),
  ]
    .join(" ")
    .toLowerCase()
    .includes(normalizedQuery);
}

function classMatchesQuery(classInfo: ClassInfo, normalizedQuery: string) {
  return classBodyMatchesQuery(classInfo, normalizedQuery) || classInfo.qualifiedName.toLowerCase().includes(normalizedQuery);
}

function ReadingGuide({
  detail,
  selectedTrace,
  loading,
}: {
  detail: ProjectDetail | null;
  selectedTrace?: BusinessTrace;
  loading: boolean;
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
    </section>
  );
}

function PackageTree({
  group,
  selectedNodeId,
  expandedPackages,
  expandedClasses,
  onTogglePackage,
  onToggleClass,
  onSelect,
}: {
  group: PackageGroup;
  selectedNodeId?: string;
  expandedPackages: Set<string>;
  expandedClasses: Set<string>;
  onTogglePackage: (packageName: string) => void;
  onToggleClass: (classId: string) => void;
  onSelect: (nodeId: string, filePath: string, line: number) => void;
}) {
  const packageExpanded = expandedPackages.has(group.name);
  return (
    <div className="package-node">
      <button
        className={`tree-row package-row ${packageExpanded ? "expanded" : ""}`}
        onClick={() => onTogglePackage(group.name)}
        title={packageExpanded ? "折叠包" : "展开包"}
      >
        {packageExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span>{group.name}</span>
        <small>{group.classes.length} 个类型</small>
      </button>
      {packageExpanded ? (
        <div className="class-list">
          {group.classes.map((classInfo) => {
            const classExpanded = expandedClasses.has(classInfo.id);
            return (
              <div className="class-node" key={classInfo.id}>
                <button
                  className={`tree-row class-row ${selectedNodeId === classInfo.id ? "active" : ""} ${classExpanded ? "expanded" : ""}`}
                  onClick={() => {
                    onSelect(classInfo.id, classInfo.filePath, classInfo.beginLine);
                    onToggleClass(classInfo.id);
                  }}
                  title={classExpanded ? "折叠类型" : "展开类型"}
                >
                  {classExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  <span>
                    <Braces size={13} />
                    {classInfo.name}
                  </span>
                  <small>{localizedKind(classInfo.kind)}</small>
                </button>
                {classExpanded ? (
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
                ) : null}
              </div>
            );
          })}
        </div>
      ) : null}
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
      const orderedIds = selectedTrace?.nodeIds.filter((nodeId) => methodNodes.some((node) => node.id === nodeId)) ?? methodNodes.map((node) => node.id);
      orderedIds.forEach((nodeId, index) => positionOf.set(nodeId, wrappedTracePosition(index, orderedIds.length)));
      classNodes.forEach((node, index) => positionOf.set(node.id, { x: 48 + index * 250, y: 38 }));
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
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        type: focused.mode === "trace" ? "smoothstep" : "default",
        className: graphEdgeClass(edge),
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
  const visibleNodes = orderAiGraphNodes(
    aiGraph.nodes.filter((node) => sourceNodesById.has(node.id)),
    aiGraph.edges,
    selectedNodeId,
  );
  const visibleNodeIds = new Set(visibleNodes.map((node) => node.id));
  const columns = graphColumnCount(visibleNodes.length);
  return {
    scope: `${aiGraph.title} · ${visibleNodes.length} 个关键节点 · ${aiGraph.summary}`,
    nodes: visibleNodes.map((node, index) => {
      const sourceNode = sourceNodesById.get(node.id);
      return {
        id: node.id,
        position: gridPosition(index, columns, 56, 76, 330, 176),
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
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
        type: "smoothstep",
        className: "edge-ai",
        style: { strokeWidth: 2.4 },
      } satisfies Edge)),
  };
}

function orderAiGraphNodes(nodes: AiCallGraphResponse["nodes"], edges: AiCallGraphResponse["edges"], selectedNodeId?: string) {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const incoming = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  for (const node of nodes) {
    incoming.set(node.id, 0);
    outgoing.set(node.id, []);
  }
  edges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      return;
    }
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1);
  });

  const originalIndex = new Map(nodes.map((node, index) => [node.id, index]));
  const queue = nodes
    .filter((node) => (incoming.get(node.id) ?? 0) === 0)
    .sort((left, right) => aiNodeRank(left.id, selectedNodeId, originalIndex) - aiNodeRank(right.id, selectedNodeId, originalIndex));
  const ordered: typeof nodes = [];
  while (queue.length) {
    const node = queue.shift()!;
    if (ordered.some((item) => item.id === node.id)) {
      continue;
    }
    ordered.push(node);
    for (const targetId of outgoing.get(node.id) ?? []) {
      incoming.set(targetId, Math.max(0, (incoming.get(targetId) ?? 0) - 1));
      if ((incoming.get(targetId) ?? 0) === 0) {
        const target = nodes.find((candidate) => candidate.id === targetId);
        if (target) {
          queue.push(target);
          queue.sort((left, right) => aiNodeRank(left.id, selectedNodeId, originalIndex) - aiNodeRank(right.id, selectedNodeId, originalIndex));
        }
      }
    }
  }

  const remaining = nodes.filter((node) => !ordered.some((item) => item.id === node.id));
  return [...ordered, ...remaining].sort((left, right) => {
    if (left.id === selectedNodeId) {
      return -1;
    }
    if (right.id === selectedNodeId) {
      return 1;
    }
    return 0;
  });
}

function aiNodeRank(nodeId: string, selectedNodeId: string | undefined, originalIndex: Map<string, number>) {
  return (nodeId === selectedNodeId ? -100 : 0) + (originalIndex.get(nodeId) ?? 0);
}

function graphColumnCount(count: number) {
  if (count <= 1) {
    return 1;
  }
  if (count <= 4) {
    return count;
  }
  return Math.min(4, Math.ceil(count / 2));
}

function gridPosition(index: number, columns: number, startX: number, startY: number, xGap: number, yGap: number) {
  return {
    x: startX + (index % columns) * xGap,
    y: startY + Math.floor(index / columns) * yGap,
  };
}

function wrappedTracePosition(index: number, total: number) {
  const columns = graphColumnCount(total);
  const row = Math.floor(index / columns);
  const col = index % columns;
  return {
    x: 56 + col * 300,
    y: 92 + row * 144,
  };
}

function graphEdgeClass(edge: GraphEdge) {
  const dispatch = typeof edge.data?.dispatch === "string" ? edge.data.dispatch : "";
  return [
    `edge-${edge.type.toLowerCase()}`,
    dispatch === "interface-implementation" ? "edge-implementation" : "",
  ].filter(Boolean).join(" ");
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

function buildRecommendedFlows(detail: ProjectDetail): BusinessTrace[] {
  if (Array.isArray(detail.flows)) {
    return detail.flows.map(flowInfoToTrace);
  }
  return buildBusinessTraces(detail);
}

function flowInfoToTrace(flow: FlowInfo): BusinessTrace {
  return {
    id: flow.id,
    title: flow.title,
    summary: flow.summary,
    nodeIds: flow.nodeIds,
    sourceKind: flow.sourceKind,
    confidence: flow.confidence,
    tags: flow.tags,
    steps: flow.steps,
    isBackendFlow: true,
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
    .sort((left, right) => flowDisplayScore(right) - flowDisplayScore(left))
    .slice(0, 12);
}

function flowDisplayScore(trace: BusinessTrace) {
  const preferredLength = trace.nodeIds.length >= 3 && trace.nodeIds.length <= 6 ? 12 : 0;
  const confidence = typeof trace.confidence === "number" ? trace.confidence * 8 : 0;
  const backendBoost = trace.isBackendFlow ? 4 : 0;
  const lengthPenalty = Math.max(0, trace.nodeIds.length - 6) * 2;
  return preferredLength + confidence + backendBoost + Math.min(trace.nodeIds.length, 6) - lengthPenalty;
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

function classByNodeId(detail: ProjectDetail, nodeId: string) {
  return detail.classes.find((classInfo) => classInfo.id === nodeId || classInfo.methods.some((method) => method.id === nodeId));
}

function fieldById(detail: ProjectDetail, fieldId: string) {
  for (const classInfo of detail.classes) {
    const field = classInfo.fields.find((item) => item.id === fieldId);
    if (field) {
      return { field, owner: classInfo };
    }
  }
  return undefined;
}

function sourceRangeForNode(detail: ProjectDetail, nodeId: string) {
  const classInfo = classByNodeId(detail, nodeId);
  if (!classInfo) {
    const fieldMatch = fieldById(detail, nodeId);
    if (!fieldMatch) {
      return null;
    }
    return {
      label: `${fieldMatch.owner.name}.${fieldMatch.field.name}`,
      filePath: fieldMatch.owner.filePath,
      beginLine: fieldMatch.field.beginLine,
      endLine: fieldMatch.field.endLine,
    };
  }
  const method = classInfo.methods.find((item) => item.id === nodeId);
  if (method) {
    return {
      label: `${classInfo.name}.${method.name}`,
      filePath: classInfo.filePath,
      beginLine: method.beginLine,
      endLine: method.endLine,
    };
  }
  return {
    label: classInfo.name,
    filePath: classInfo.filePath,
    beginLine: classInfo.beginLine,
    endLine: classInfo.endLine,
  };
}

function buildPinnedSnippetFromContent({
  label,
  filePath,
  beginLine,
  endLine,
  nodeId,
  sourcePath,
  sourceContent,
}: {
  label: string;
  filePath: string;
  beginLine: number;
  endLine: number;
  nodeId?: string;
  sourcePath: string;
  sourceContent: string;
}): PinnedSourceSnippet | null {
  if (sourcePath !== filePath) {
    return null;
  }
  const lines = sourceContent.split("\n");
  const start = Math.max(1, beginLine);
  const end = Math.min(lines.length, Math.max(start, endLine));
  const snippetSource = lines.slice(start - 1, end).join("\n");
  const id = `${filePath}:${start}-${end}:${nodeId ?? label}`;
  return {
    id,
    label,
    filePath,
    beginLine: start,
    endLine: end,
    nodeId,
    source: snippetSource,
  };
}

function referenceKindLabel(kind: CodeReference["kind"]) {
  switch (kind) {
    case "CALL":
      return "CALL";
    case "CREATE":
      return "NEW";
    case "FIELD_READ":
      return "READ";
    case "FIELD_WRITE":
      return "WRITE";
    case "RETURN":
      return "RETURN";
    default:
      return kind;
  }
}

function referenceTargetJump(detail: ProjectDetail, reference: CodeReference): ReferenceJump | undefined {
  if (!reference.targetNodeId) {
    return undefined;
  }
  const graphNode = detail.graphNodes.find((node) => node.id === reference.targetNodeId);
  if (graphNode) {
    return {
      label: graphNode.type === "method"
        ? `跳转到 ${ownerClassName(detail, graphNode.id) ? `${ownerClassName(detail, graphNode.id)}.` : ""}${graphNode.label}`
        : `跳转到 ${graphNode.label}`,
      nodeId: graphNode.id,
      filePath: graphNode.filePath,
      line: graphNode.line,
    };
  }
  const fieldMatch = fieldById(detail, reference.targetNodeId);
  if (fieldMatch) {
    return {
      label: `跳转到字段 ${fieldMatch.owner.name}.${fieldMatch.field.name}`,
      nodeId: fieldMatch.field.id,
      filePath: fieldMatch.owner.filePath,
      line: fieldMatch.field.beginLine,
    };
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

function buildAiCacheKey({
  root,
  projectId,
  traceId,
  nodeId,
  question,
  pinsKey,
}: {
  root: string;
  projectId: string;
  traceId?: string;
  nodeId?: string;
  question: string;
  pinsKey?: string;
}) {
  const target = traceId ? `trace:${traceId}` : `node:${nodeId ?? "project"}`;
  const normalizedQuestion = question.trim() || "__default__";
  return [
    AI_CACHE_PREFIX,
    encodeURIComponent(root || "default-root"),
    encodeURIComponent(projectId),
    encodeURIComponent(target),
    encodeURIComponent(normalizedQuestion),
    encodeURIComponent(pinsKey || "no-pins"),
  ].join(":");
}

function readAiCache(key: string): AiCacheEntry | null {
  try {
    const raw = browserLocalStorage()?.getItem(key) ?? aiMemoryCache.get(key);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<AiCacheEntry>;
    return {
      outputMode: parsed.outputMode === "context" ? "context" : "explain",
      question: parsed.question ?? "",
      summary: parsed.summary ?? "",
      model: parsed.model ?? "",
      context: parsed.context ?? "",
      contextModel: parsed.contextModel ?? "",
      updatedAt: typeof parsed.updatedAt === "number" ? parsed.updatedAt : 0,
    };
  } catch {
    return null;
  }
}

function writeAiCache(key: string, entry: AiCacheEntry) {
  const value = JSON.stringify(entry);
  aiMemoryCache.set(key, value);
  try {
    browserLocalStorage()?.setItem(key, value);
  } catch {
    // Best-effort cache only; generation should not fail if the browser refuses storage.
  }
}

function removeAiCache(key: string) {
  aiMemoryCache.delete(key);
  try {
    browserLocalStorage()?.removeItem(key);
  } catch {
    // Best-effort cache only.
  }
}

function readBooleanPreference(key: string) {
  try {
    return browserLocalStorage()?.getItem(key) === "true";
  } catch {
    return false;
  }
}

function writeBooleanPreference(key: string, value: boolean) {
  try {
    browserLocalStorage()?.setItem(key, value ? "true" : "false");
  } catch {
    // Best-effort UI preference only.
  }
}

function readNumberPreference(key: string, fallback: number, min: number, max: number) {
  try {
    const rawValue = browserLocalStorage()?.getItem(key);
    if (rawValue === null || rawValue === undefined) {
      return fallback;
    }
    const value = Number(rawValue);
    return Number.isFinite(value) ? clampNumber(value, min, max) : fallback;
  } catch {
    return fallback;
  }
}

function writeNumberPreference(key: string, value: number) {
  try {
    browserLocalStorage()?.setItem(key, String(Math.round(value)));
  } catch {
    // Best-effort UI preference only.
  }
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function browserLocalStorage() {
  try {
    return typeof window !== "undefined" && window.localStorage ? window.localStorage : null;
  } catch {
    return null;
  }
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
  const primaryNodeIds = selectedTrace?.nodeIds.length
    ? selectedTrace.nodeIds
    : selectedNodeId
      ? [selectedNodeId]
      : [];
  const nodeIds = new Set([
    ...primaryNodeIds,
    ...(aiGraph?.nodes.map((node) => node.id) ?? []),
  ]);
  [...nodeIds].forEach((nodeId, index) => {
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
  detail.graphNodes
    .filter((node) => node.type === "method" && !explanations.has(node.id))
    .forEach((node) => {
      const method = methodById(detail, node.id);
      const owner = ownerClassName(detail, node.id);
      if (!method) {
        return;
      }
      const extracted = extractMethodExplanation(summary, owner, method);
      if (extracted) {
        explanations.set(node.id, {
          label: owner ? `${owner}.${method.name}` : method.name,
          text: extracted,
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

function extractFollowUpQuestions(markdown: string) {
  const lines = markdown.split(/\r?\n/);
  let headingIndex = -1;
  lines.forEach((line, index) => {
    const normalized = line.replace(/^[#>\s*-]+/, "").trim();
    if (/追问|继续问|后续问题|延伸问题|适合.*问/.test(normalized)) {
      headingIndex = index;
    }
  });
  if (headingIndex < 0) {
    return { content: markdown, questions: [] };
  }

  const before = lines.slice(0, headingIndex).join("\n").trim();
  const section = lines.slice(headingIndex + 1);
  const questions: string[] = [];
  for (const line of section) {
    const question = normalizeFollowUpQuestion(line);
    if (question) {
      questions.push(question);
    }
  }

  const uniqueQuestions = Array.from(new Set(questions)).slice(0, 4);
  if (!uniqueQuestions.length) {
    return { content: markdown, questions: [] };
  }
  return { content: before || markdown, questions: uniqueQuestions };
}

function normalizeFollowUpQuestion(line: string) {
  const stripped = line
    .trim()
    .replace(/^[-*+]\s+/, "")
    .replace(/^\d+[.)、]\s*/, "")
    .replace(/^["'“”‘’]+|["'“”‘’]+$/g, "")
    .replace(/\*\*/g, "")
    .trim();
  if (!stripped || stripped.length < 4) {
    return "";
  }
  if (!/[？?]$/.test(stripped) && stripped.length > 64) {
    return "";
  }
  return stripped;
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function lineCallsMethod(line: string, methodName: string) {
  return new RegExp(`(^|[^A-Za-z0-9_$])${escapeRegExp(methodName)}\\s*\\(`).test(line);
}

function TraceExplorer({
  detail,
  traces,
  selectedTraceId,
  selectedNodeId,
  collapsed,
  aiFlowLoading,
  aiFlowError,
  onToggleCollapsed,
  onGenerateAiFlows,
  onSelectNode,
  onSelectTrace,
}: {
  detail: ProjectDetail | null;
  traces: BusinessTrace[];
  selectedTraceId: string;
  selectedNodeId?: string;
  collapsed: boolean;
  aiFlowLoading: boolean;
  aiFlowError: string;
  onToggleCollapsed: () => void;
  onGenerateAiFlows: () => void;
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
        <button className="trace-collapse-rail" type="button" onClick={onToggleCollapsed} title="展开推荐 Flow 和步骤">
          <PanelBottomOpen size={15} />
          <strong>推荐 Flow / 步骤</strong>
          <span>{selectedTrace ? selectedTrace.title : "展开查看阅读路径"}</span>
        </button>
      </section>
    );
  }
  return (
    <section className="reading-path">
      <div className="trace-column">
        <div className="trace-header-row">
          <PanelTitle icon={<Route size={16} />} title="推荐 Flow" />
          <div className="trace-header-actions">
            <Button
              htmlType="button"
              onClick={onGenerateAiFlows}
              disabled={aiFlowLoading}
              title="基于当前选中类或方法生成推荐 Flow"
              icon={<Sparkles size={15} />}
            />
            <Button
              htmlType="button"
              onClick={onToggleCollapsed}
              title="折叠推荐 Flow 和步骤"
              icon={<PanelBottomClose size={15} />}
            />
          </div>
        </div>
        <div className="trace-list">
          {aiFlowLoading ? <div className="trace-feedback">AI 正在生成推荐 Flow...</div> : null}
          {aiFlowError ? <div className="trace-feedback error">{aiFlowError}</div> : null}
          {!aiFlowLoading && !traces.length ? (
            <div className="trace-empty">选中一个类或方法后，点击上方星标生成推荐 Flow。</div>
          ) : null}
          {traces.map((trace) => (
            <button
              key={trace.id}
              className={trace.id === selectedTraceId ? "active" : ""}
              onClick={() => onSelectTrace(trace)}
            >
              <strong>{trace.title}</strong>
              <span>{trace.summary}</span>
              <FlowMeta trace={trace} />
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
            const flowStep = selectedTrace?.steps?.[index];
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
                <strong>{flowStep?.title ?? (ownerClassName(detail, node.id) ? `${ownerClassName(detail, node.id)}.${node.label}` : node.label)}</strong>
                <span>{flowStep?.description ?? describeStep(detail, node, index)}</span>
                <StateFacts step={flowStep} />
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

function FlowMeta({ trace }: { trace: BusinessTrace }) {
  if (!trace.isBackendFlow) {
    return null;
  }
  const confidence = typeof trace.confidence === "number" ? `${Math.round(trace.confidence * 100)}%` : "";
  return (
    <div className="flow-meta">
      {trace.sourceKind ? <small>{trace.sourceKind}</small> : null}
      {confidence ? <small>{confidence}</small> : null}
      <small>{trace.nodeIds.length} 步</small>
      {(trace.tags ?? []).slice(0, 3).map((tag) => (
        <small key={tag}>{tag}</small>
      ))}
    </div>
  );
}

function StateFacts({ step }: { step?: FlowStep }) {
  if (!step) {
    return null;
  }
  const reads = step.stateReads?.slice(0, 3) ?? [];
  const writes = step.stateWrites?.slice(0, 3) ?? [];
  if (!reads.length && !writes.length) {
    return null;
  }
  return (
    <div className="state-facts">
      {reads.length ? <small>读 {reads.join("、")}</small> : null}
      {writes.length ? <small>写 {writes.join("、")}</small> : null}
    </div>
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
  pinnedSnippets,
  onQuestionChange,
  onAsk,
  onGenerateContext,
  onCopyContext,
  onClearHistory,
  onRemovePin,
  onClearPins,
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
  pinnedSnippets: PinnedSourceSnippet[];
  onQuestionChange: (value: string) => void;
  onAsk: (questionOverride?: string) => void;
  onGenerateContext: () => void;
  onCopyContext: () => void;
  onClearHistory: () => void;
  onRemovePin: (id: string) => void;
  onClearPins: () => void;
  onOpenSource: (link: SourceLink) => void;
}) {
  const activeModel = outputMode === "context" ? contextModel : model;
  const activeError = outputMode === "context" ? contextError : error;
  const hasHistory = Boolean(summary || model || error || context || contextModel || contextError);
  const summaryResult = useMemo(() => extractFollowUpQuestions(summary), [summary]);

  function askFollowUp(nextQuestion: string) {
    if (disabled || loading || contextLoading) {
      return;
    }
    onQuestionChange(nextQuestion);
    onAsk(nextQuestion);
  }

  return (
    <div className="ai-panel">
      <div className="ai-box">
        <TextArea
          value={question}
          onChange={(event) => onQuestionChange(event.target.value)}
          placeholder="问这条链路，或描述你准备让代码模型完成的改动目标"
          disabled={disabled || loading || contextLoading}
          autoSize={{ minRows: 3, maxRows: 6 }}
        />
        <div className="ai-actions">
          {activeModel ? <span>模型：{activeModel}</span> : <span>选择调用链后生成解释或编码 Context</span>}
        </div>
        <div className="ai-command-row">
          <Button
            className={outputMode === "explain" ? "active" : ""}
            htmlType="button"
            onClick={() => onAsk()}
            disabled={disabled || loading || contextLoading}
            title="解释当前链路"
            icon={loading ? <RefreshCw size={14} /> : <Send size={14} />}
          >
            {loading ? "生成中" : summary ? "重新解释" : "解释"}
          </Button>
          <Button
            className={outputMode === "context" ? "active" : ""}
            htmlType="button"
            onClick={onGenerateContext}
            disabled={disabled || loading || contextLoading}
            title="生成可粘贴给代码模型的链路 Context"
            icon={contextLoading ? <RefreshCw size={14} /> : <Clipboard size={14} />}
          >
            {contextLoading ? "生成中" : "Context"}
          </Button>
          <Button
            htmlType="button"
            onClick={onCopyContext}
            disabled={!context || contextLoading}
            title="复制 Context"
            icon={contextCopied ? <ClipboardCheck size={14} /> : <Clipboard size={14} />}
          >
            {contextCopied ? "已复制" : "复制"}
          </Button>
          <Button
            className="clear-history"
            htmlType="button"
            onClick={onClearHistory}
            disabled={disabled || loading || contextLoading || !hasHistory}
            title="清空 AI 输出历史"
            icon={<Trash2 size={14} />}
          >
            清空
          </Button>
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
        {pinnedSnippets.length ? (
          <div className="pinned-snippets" aria-label="固定源码片段">
            <div>
              <strong>固定 Context</strong>
              <Button htmlType="button" size="small" onClick={onClearPins} title="清空固定片段" icon={<Trash2 size={12} />} />
            </div>
            <div>
              {pinnedSnippets.map((snippet) => (
                <span key={snippet.id}>
                  <Pin size={11} />
                  <strong>{snippet.label}</strong>
                  <small>{fileLocationLabel(snippet.filePath, snippet.beginLine)}</small>
                  <Button
                    htmlType="button"
                    size="small"
                    onClick={() => onRemovePin(snippet.id)}
                    title="移除固定片段"
                    icon={<X size={11} />}
                  />
                </span>
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
            <>
              <ReactMarkdown>{summaryResult.content}</ReactMarkdown>
              {summaryResult.questions.length ? (
                <div className="ai-follow-ups" aria-label="适合继续追问的问题">
                  <strong>继续追问</strong>
                  <div>
                    {summaryResult.questions.map((nextQuestion) => (
                      <button
                        key={nextQuestion}
                        type="button"
                        onClick={() => askFollowUp(nextQuestion)}
                        disabled={disabled || loading || contextLoading}
                      >
                        {nextQuestion}
                      </button>
                    ))}
                  </div>
                </div>
              ) : null}
            </>
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
  pinnedCount,
  canPinTrace,
  openIdeStatus,
  canGoBack,
  canGoForward,
  onBack,
  onForward,
  onOpenInIde,
  onPinCurrent,
  onPinTrace,
  onJump,
}: {
  source: SourceResponse | null;
  highlightLine?: number;
  selectedNode?: GraphNode;
  detail: ProjectDetail | null;
  methodExplanations: Map<string, MethodExplanation>;
  pinnedCount: number;
  canPinTrace: boolean;
  openIdeStatus: string;
  canGoBack: boolean;
  canGoForward: boolean;
  onBack: () => void;
  onForward: () => void;
  onOpenInIde: () => void;
  onPinCurrent: () => void;
  onPinTrace: () => void;
  onJump: (jump: SourceLineJump) => void;
}) {
  const sourcePreRef = useRef<HTMLPreElement | null>(null);
  const lines = useMemo(() => source?.content.split("\n") ?? [], [source]);
  const activeRange = useMemo(() => {
    if (!selectedNode || !detail) {
      return null;
    }
    const owner = classByNodeId(detail, selectedNode.id);
    if (!owner) {
      return null;
    }
    const method = owner.methods.find((item) => item.id === selectedNode.id);
    return method
      ? { begin: method.beginLine, end: method.endLine }
      : { begin: owner.beginLine, end: owner.endLine };
  }, [detail, selectedNode]);
  const lineExplanations = useMemo(() => {
    const annotations = new Map<number, SourceLineExplanation>();
    if (!source || !detail || !methodExplanations.size) {
      return annotations;
    }
    const fileClasses = detail.classes.filter((classInfo) => classInfo.filePath === source.path);
    fileClasses.forEach((classInfo) => {
      classInfo.methods.forEach((method) => {
        const explanation = methodExplanations.get(method.id);
        if (explanation) {
          for (let lineNo = method.beginLine; lineNo <= method.endLine; lineNo += 1) {
            annotations.set(lineNo, {
              ...explanation,
              entryLine: method.beginLine,
              kind: lineNo === method.beginLine ? "entry" : "body",
              triggerText: lineNo === method.beginLine ? method.name : undefined,
            });
          }
        }
      });
    });
    detail.references
      .filter((reference) => reference.kind === "CALL" && reference.span.filePath === source.path)
      .forEach((reference) => {
        const explanation = methodExplanations.get(reference.targetNodeId);
        if (!explanation) {
          return;
        }
        annotations.set(reference.span.beginLine, {
          label: `调用 ${explanation.label}`,
          text: explanation.text,
          entryLine: reference.span.beginLine,
          kind: "call",
          triggerText: reference.symbol,
        });
      });
    return annotations;
  }, [detail, methodExplanations, source]);
  const lineReferences = useMemo(() => buildSourceLineReferences(source, detail), [detail, source]);
  const lineJumps = useMemo(() => buildDeclarationLineJumps(source, detail), [detail, source]);
  useEffect(() => {
    if (!highlightLine || !sourcePreRef.current) {
      return;
    }
    const target = sourcePreRef.current.querySelector<HTMLElement>(`[data-line="${highlightLine}"]`);
    target?.scrollIntoView({ block: "center", inline: "nearest" });
  }, [highlightLine, source?.path]);
  if (!source) {
    return <div className="source-empty">还没有选择源码文件。</div>;
  }
  return (
    <div className="source-view">
      <div className="source-toolbar">
        <div className="source-nav-actions" aria-label="源码跳转历史">
          <Button htmlType="button" onClick={onBack} disabled={!canGoBack} title="后退到上一次源码跳转" icon={<ChevronLeft size={14} />} />
          <Button htmlType="button" onClick={onForward} disabled={!canGoForward} title="前进到下一次源码跳转" icon={<ChevronRight size={14} />} />
        </div>
        <div className="source-path">{source.path}{highlightLine ? `:${highlightLine}` : ""}</div>
        <div className="source-toolbar-actions">
          <Button htmlType="button" onClick={onPinCurrent} title="固定当前源码片段" icon={<Pin size={14} />}>
            {pinnedCount || ""}
          </Button>
          <Button htmlType="button" onClick={onPinTrace} disabled={!canPinTrace} title="固定当前调用链节点" icon={<PinOff size={14} />} />
          <Button htmlType="button" onClick={onOpenInIde} title="在 IDE 打开" icon={<ExternalLink size={14} />} />
        </div>
        {openIdeStatus ? <div className="source-toolbar-status">{openIdeStatus}</div> : null}
      </div>
      <pre ref={sourcePreRef}>
        {lines.map((line, index) => {
          const lineNo = index + 1;
          const inRange = activeRange ? lineNo >= activeRange.begin && lineNo <= activeRange.end : false;
          const explanation = lineExplanations.get(lineNo);
          const jump = lineJumps.get(lineNo);
          const references = lineReferences.get(lineNo) ?? [];
          return (
            <div
              key={lineNo}
              data-line={lineNo}
              className={`code-line ${inRange ? "in-range" : ""} ${lineNo === highlightLine ? "highlight" : ""} ${explanation ? "has-ai-explanation" : ""}`}
            >
              <span className="line-no">{lineNo}</span>
              {explanation ? (
                <span className={`ai-source-note ${explanation.kind}`}>
                  <span className="ai-source-badge">{explanation.kind === "call" ? "CALL" : "AI"}</span>
                  <span className="source-tooltip" role="tooltip">
                    <strong>{explanation.label}</strong>
                    <span>{explanation.text}</span>
                  </span>
                </span>
              ) : <span className="ai-source-note-spacer" />}
              <code>
                <CodeLineContent
                  line={line}
                  references={references}
                  explanation={explanation}
                  jump={jump}
                  onJump={onJump}
                  onReferenceJump={(referenceJump, reference) => onJump({
                    triggerText: reference.symbol,
                    label: referenceJump.label,
                    nodeId: referenceJump.nodeId,
                    filePath: referenceJump.filePath,
                    line: referenceJump.line,
                  })}
                />
              </code>
            </div>
          );
        })}
      </pre>
    </div>
  );
}

function buildSourceLineReferences(source: SourceResponse | null, detail: ProjectDetail | null) {
  const referencesByLine = new Map<number, SourceLineReference[]>();
  if (!source || !detail) {
    return referencesByLine;
  }
  detail.references
    .filter((reference) => reference.span.filePath === source.path)
    .forEach((reference) => {
      const lineReferences = referencesByLine.get(reference.span.beginLine) ?? [];
      lineReferences.push({
        reference,
        jump: referenceTargetJump(detail, reference),
      });
      referencesByLine.set(reference.span.beginLine, lineReferences);
    });
  referencesByLine.forEach((lineReferences) => {
    lineReferences.sort((left, right) => left.reference.span.beginColumn - right.reference.span.beginColumn);
  });
  return referencesByLine;
}

function buildDeclarationLineJumps(source: SourceResponse | null, detail: ProjectDetail | null) {
  const jumps = new Map<number, SourceLineJump>();
  if (!source || !detail) {
    return jumps;
  }
  const fileClasses = detail.classes.filter((classInfo) => classInfo.filePath === source.path);
  fileClasses.forEach((classInfo) => {
    jumps.set(classInfo.beginLine, {
      triggerText: classInfo.name,
      label: `跳转到类 ${classInfo.name}`,
      nodeId: classInfo.id,
      filePath: classInfo.filePath,
      line: classInfo.beginLine,
    });
    classInfo.methods.forEach((method) => {
      jumps.set(method.beginLine, {
        triggerText: method.name,
        label: `跳转到方法 ${classInfo.name}.${method.name}`,
        nodeId: method.id,
        filePath: classInfo.filePath,
        line: method.beginLine,
      });
    });
  });
  return jumps;
}

function CodeLineContent({
  line,
  references,
  explanation,
  jump,
  onJump,
  onReferenceJump,
}: {
  line: string;
  references: SourceLineReference[];
  explanation?: SourceLineExplanation;
  jump?: SourceLineJump;
  onJump: (jump: SourceLineJump) => void;
  onReferenceJump: (jump: ReferenceJump, reference: CodeReference) => void;
}) {
  if (references.length) {
    return (
      <>
        {splitLineByReferences(line, references).map((segment, index) => {
          if (typeof segment === "string") {
            return <React.Fragment key={`text-${index}`}>{segment}</React.Fragment>;
          }
          return (
            <ReferenceToken
              key={segment.reference.id}
              segment={segment}
              onReferenceJump={onReferenceJump}
            />
          );
        })}
      </>
    );
  }
  const triggerText = explanation?.triggerText ?? jump?.triggerText;
  const code = line || " ";
  if (!triggerText) {
    return <>{code}</>;
  }
  const index = line.indexOf(triggerText);
  if (index < 0) {
    return <>{code}</>;
  }
  const before = line.slice(0, index);
  const after = line.slice(index + triggerText.length);
  const title = [jump?.label, explanation ? "hover 查看 AI 解释" : ""].filter(Boolean).join(" · ");
  return (
    <>
      {before}
      <button
        className={`source-code-trigger ${jump ? "jumpable" : ""}`}
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          if (jump) {
            onJump(jump);
          }
        }}
        title={title || undefined}
      >
        {triggerText}
        {explanation ? (
          <span className="source-tooltip code-tooltip" role="tooltip">
            <strong>{explanation.label}</strong>
            <span>{explanation.text}</span>
          </span>
        ) : null}
      </button>
      {after}
    </>
  );
}

function splitLineByReferences(line: string, references: SourceLineReference[]) {
  const segments: Array<string | SourceLineReference> = [];
  const code = line || " ";
  let cursor = 0;
  references.forEach((lineReference) => {
    const reference = lineReference.reference;
    const start = clampNumber(reference.span.beginColumn - 1, cursor, code.length);
    const preferredEnd = reference.span.beginLine === reference.span.endLine
      ? reference.span.endColumn
      : reference.span.beginColumn - 1 + Math.max(reference.symbol.length, 1);
    const end = clampNumber(Math.max(start + 1, preferredEnd), start + 1, code.length);
    if (start < cursor || start >= code.length) {
      return;
    }
    if (start > cursor) {
      segments.push(code.slice(cursor, start));
    }
    segments.push({
      ...lineReference,
      reference: {
        ...reference,
        symbol: code.slice(start, end) || reference.symbol,
      },
    });
    cursor = end;
  });
  if (cursor < code.length) {
    segments.push(code.slice(cursor));
  }
  return segments.length ? segments : [code];
}

function ReferenceToken({
  segment,
  onReferenceJump,
}: {
  segment: SourceLineReference;
  onReferenceJump: (jump: ReferenceJump, reference: CodeReference) => void;
}) {
  const reference = segment.reference;
  const jump = segment.jump;
  const title = [reference.detail, jump?.label].filter(Boolean).join(" · ");
  return (
    <button
      className={`source-code-trigger reference-token ${reference.kind.toLowerCase().replace("_", "-")} ${jump ? "jumpable" : ""}`}
      type="button"
      onClick={(event) => {
        event.stopPropagation();
        if (jump) {
          onReferenceJump(jump, reference);
        }
      }}
      title={title || undefined}
    >
      {reference.symbol}
    </button>
  );
}

function fileLocationLabel(filePath: string, line: number) {
  const fileName = filePath.split(/[\\/]/).pop() ?? filePath;
  return `${fileName}:${line}`;
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: "#245b4f",
          colorInfo: "#245b4f",
          borderRadius: 6,
          fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
        },
        components: {
          Button: {
            controlHeight: 30,
            borderRadius: 6,
          },
          Input: {
            controlHeight: 30,
            borderRadius: 6,
          },
          Segmented: {
            itemSelectedBg: "#eef4f2",
            itemSelectedColor: "#245b4f",
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);

export type WorkspaceSnapshot = {
  root: string;
  projects: ProjectSummary[];
};

export type ProjectSummary = {
  id: string;
  name: string;
  path: string;
  buildType: string;
  javaFileCount: number;
  classCount: number;
  methodCount: number;
  packages: string[];
  entryPoints: string[];
  concepts: string[];
};

export type ProjectDetail = {
  summary: ProjectSummary;
  files: SourceFile[];
  classes: ClassInfo[];
  graphNodes: GraphNode[];
  graphEdges: GraphEdge[];
  references: CodeReference[];
  readingPath: ReadingStep[];
  flows: FlowInfo[];
};

export type SourceFile = {
  path: string;
  relativePath: string;
  packageName: string;
  classes: string[];
};

export type ClassInfo = {
  id: string;
  name: string;
  qualifiedName: string;
  kind: string;
  packageName: string;
  filePath: string;
  beginLine: number;
  endLine: number;
  fields: FieldInfo[];
  methods: MethodInfo[];
  nestedClasses: string[];
  concepts: string[];
  superTypes: string[];
};

export type FieldInfo = {
  id: string;
  name: string;
  type: string;
  beginLine: number;
  endLine: number;
};

export type MethodInfo = {
  id: string;
  name: string;
  signature: string;
  returnType: string;
  beginLine: number;
  endLine: number;
  calls: string[];
  creates: string[];
  concepts: string[];
  kind: string;
  parameterTypes: string[];
  callSites: CallSiteInfo[];
  readsFields: string[];
  writesFields: string[];
  branchFacts: string[];
  exceptionFacts: string[];
};

export type CallSiteInfo = {
  kind: string;
  name: string;
  targetOwner: string;
  targetSignature: string;
  targetId: string | null;
  receiverType: string;
  line: number | null;
  resolved: boolean;
  inLambda: boolean;
  argumentTypes: string[];
};

export type GraphNode = {
  id: string;
  label: string;
  type: "class" | "method";
  filePath: string;
  line: number;
  data: Record<string, unknown>;
};

export type GraphEdge = {
  id: string;
  source: string;
  target: string;
  type: string;
  label: string;
  data: Record<string, unknown>;
};

export type SourceSpan = {
  filePath: string;
  beginLine: number;
  beginColumn: number;
  endLine: number;
  endColumn: number;
};

export type CodeReference = {
  id: string;
  kind: "CALL" | "FIELD_READ" | "FIELD_WRITE" | "CREATE" | "RETURN";
  sourceNodeId: string;
  targetNodeId: string;
  symbol: string;
  detail: string;
  span: SourceSpan;
};

export type ReadingStep = {
  id: string;
  title: string;
  description: string;
  targetNodeId: string;
  filePath: string;
  line: number;
};

export type FlowInfo = {
  id: string;
  title: string;
  summary: string;
  entryNodeId: string;
  nodeIds: string[];
  sourceKind: string;
  confidence: number;
  tags: string[];
  steps: FlowStep[];
};

export type FlowStep = {
  nodeId: string;
  title: string;
  description: string;
  filePath: string;
  line: number;
  stateReads: string[];
  stateWrites: string[];
};

export type SourceResponse = {
  path: string;
  content: string;
};

export type AiSummaryRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
  flowId?: string;
  trace?: {
    id: string;
    title: string;
    summary: string;
    nodeIds: string[];
  };
  question?: string;
};

export type AiSummaryResponse = {
  content: string;
  model: string;
};

export type AiCallGraphRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
  flowId?: string;
  trace?: {
    id: string;
    title: string;
    summary: string;
    nodeIds: string[];
  };
};

export type AiFlowRecommendationRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
  instruction?: string;
};

export type AiFlowRecommendationResponse = {
  flows: FlowInfo[];
  model: string;
};

export type AiCodingContextRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
  flowId?: string;
  trace?: {
    id: string;
    title: string;
    summary: string;
    nodeIds: string[];
  };
  task?: string;
  pinnedSnippets?: PinnedSourceSnippet[];
};

export type AiCallGraphResponse = {
  title: string;
  summary: string;
  nodes: AiCallGraphNode[];
  edges: AiCallGraphEdge[];
  model: string;
};

export type AiCodingContextResponse = {
  content: string;
  model: string;
};

export type PinnedSourceSnippet = {
  id: string;
  label: string;
  filePath: string;
  beginLine: number;
  endLine: number;
  nodeId?: string;
  source: string;
};

export type OpenInIdeResponse = {
  ok: boolean;
  message: string;
  command: string;
};

export type TerminalSessionRequest = {
  projectId: string;
  root: string;
  cwd: string;
  cols: number;
  rows: number;
};

export type TerminalSessionInfo = {
  id: string;
  title: string;
  cwd: string;
  projectId: string;
  shell: string;
  createdAt: string;
  alive: boolean;
};

export type TerminalSessionListResponse = {
  sessions: TerminalSessionInfo[];
};

export type AiCallGraphNode = {
  id: string;
  label: string;
  subtitle: string;
  explanation: string;
  importance: "core" | "supporting";
  filePath: string;
  line: number;
};

export type AiCallGraphEdge = {
  id: string;
  source: string;
  target: string;
  label: string;
  explanation: string;
};

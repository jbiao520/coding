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
  readingPath: ReadingStep[];
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
};

export type FieldInfo = {
  name: string;
  type: string;
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
};

export type ReadingStep = {
  id: string;
  title: string;
  description: string;
  targetNodeId: string;
  filePath: string;
  line: number;
};

export type SourceResponse = {
  path: string;
  content: string;
};

export type AiSummaryRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
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
  trace?: {
    id: string;
    title: string;
    summary: string;
    nodeIds: string[];
  };
};

export type AiCodingContextRequest = {
  projectId: string;
  root: string;
  selectedNodeId?: string;
  trace?: {
    id: string;
    title: string;
    summary: string;
    nodeIds: string[];
  };
  task?: string;
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

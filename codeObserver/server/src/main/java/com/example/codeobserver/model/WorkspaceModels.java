package com.example.codeobserver.model;

import java.util.List;
import java.util.Map;

public final class WorkspaceModels {
    private WorkspaceModels() {
    }

    public record WorkspaceSnapshot(
            String root,
            List<ProjectSummary> projects
    ) {
    }

    public record ProjectSummary(
            String id,
            String name,
            String path,
            String buildType,
            int javaFileCount,
            int classCount,
            int methodCount,
            List<String> packages,
            List<String> entryPoints,
            List<String> concepts
    ) {
    }

    public record ProjectDetail(
            ProjectSummary summary,
            List<SourceFile> files,
            List<ClassInfo> classes,
            List<GraphNode> graphNodes,
            List<GraphEdge> graphEdges,
            List<CodeReference> references,
            List<ReadingStep> readingPath,
            List<FlowInfo> flows
    ) {
    }

    public record SourceFile(
            String path,
            String relativePath,
            String packageName,
            List<String> classes
    ) {
    }

    public record ClassInfo(
            String id,
            String name,
            String qualifiedName,
            String kind,
            String packageName,
            String filePath,
            int beginLine,
            int endLine,
            List<FieldInfo> fields,
            List<MethodInfo> methods,
            List<String> nestedClasses,
            List<String> concepts,
            List<String> superTypes
    ) {
    }

    public record FieldInfo(
            String id,
            String name,
            String type,
            int beginLine,
            int endLine
    ) {
    }

    public record MethodInfo(
            String id,
            String name,
            String signature,
            String returnType,
            int beginLine,
            int endLine,
            List<String> calls,
            List<String> creates,
            List<String> concepts,
            String kind,
            List<String> parameterTypes,
            List<CallSiteInfo> callSites,
            List<String> readsFields,
            List<String> writesFields,
            List<String> branchFacts,
            List<String> exceptionFacts
    ) {
    }

    public record CallSiteInfo(
            String kind,
            String name,
            String targetOwner,
            String targetSignature,
            String targetId,
            String receiverType,
            Integer line,
            Boolean resolved,
            Boolean inLambda,
            List<String> argumentTypes
    ) {
    }

    public record GraphNode(
            String id,
            String label,
            String type,
            String filePath,
            Integer line,
            Map<String, Object> data
    ) {
    }

    public record GraphEdge(
            String id,
            String source,
            String target,
            String type,
            String label,
            Map<String, Object> data
    ) {
    }

    public record SourceSpan(
            String filePath,
            int beginLine,
            int beginColumn,
            int endLine,
            int endColumn
    ) {
    }

    public record CodeReference(
            String id,
            String kind,
            String sourceNodeId,
            String targetNodeId,
            String symbol,
            String detail,
            SourceSpan span
    ) {
    }

    public record ReadingStep(
            String id,
            String title,
            String description,
            String targetNodeId,
            String filePath,
            int line
    ) {
    }

    public record FlowInfo(
            String id,
            String title,
            String summary,
            String entryNodeId,
            List<String> nodeIds,
            String sourceKind,
            double confidence,
            List<String> tags,
            List<FlowStep> steps
    ) {
    }

    public record FlowStep(
            String nodeId,
            String title,
            String description,
            String filePath,
            int line,
            List<String> stateReads,
            List<String> stateWrites
    ) {
    }

    public record SourceResponse(
            String path,
            String content
    ) {
    }

    public record AiSummaryRequest(
            String projectId,
            String root,
            String selectedNodeId,
            String flowId,
            FlowTrace trace,
            String question
    ) {
    }

    public record AiCallGraphRequest(
            String projectId,
            String root,
            String selectedNodeId,
            String flowId,
            FlowTrace trace
    ) {
    }

    public record AiFlowRecommendationRequest(
            String projectId,
            String root,
            String selectedNodeId,
            String instruction
    ) {
    }

    public record AiCodingContextRequest(
            String projectId,
            String root,
            String selectedNodeId,
            String flowId,
            FlowTrace trace,
            String task,
            List<PinnedSourceSnippet> pinnedSnippets
    ) {
    }

    public record PinnedSourceSnippet(
            String id,
            String label,
            String filePath,
            int beginLine,
            int endLine,
            String nodeId,
            String source
    ) {
    }

    public record FlowTrace(
            String id,
            String title,
            String summary,
            List<String> nodeIds
    ) {
    }

    public record AiSummaryResponse(
            String content,
            String model
    ) {
    }

    public record AiCallGraphResponse(
            String title,
            String summary,
            List<AiCallGraphNode> nodes,
            List<AiCallGraphEdge> edges,
            String model
    ) {
    }

    public record AiFlowRecommendationResponse(
            List<FlowInfo> flows,
            String model
    ) {
    }

    public record AiCodingContextResponse(
            String content,
            String model
    ) {
    }

    public record OpenInIdeRequest(
            String path,
            int line
    ) {
    }

    public record OpenInIdeResponse(
            boolean ok,
            String message,
            String command
    ) {
    }

    public record AiCallGraphNode(
            String id,
            String label,
            String subtitle,
            String explanation,
            String importance,
            String filePath,
            Integer line
    ) {
    }

    public record AiCallGraphEdge(
            String id,
            String source,
            String target,
            String label,
            String explanation
    ) {
    }
}

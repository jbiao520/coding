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
            List<ReadingStep> readingPath
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
            List<String> concepts
    ) {
    }

    public record FieldInfo(
            String name,
            String type
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
            List<String> concepts
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
            String label
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

    public record SourceResponse(
            String path,
            String content
    ) {
    }

    public record AiSummaryRequest(
            String projectId,
            String root,
            String selectedNodeId,
            FlowTrace trace,
            String question
    ) {
    }

    public record AiCallGraphRequest(
            String projectId,
            String root,
            String selectedNodeId,
            FlowTrace trace
    ) {
    }

    public record AiCodingContextRequest(
            String projectId,
            String root,
            String selectedNodeId,
            FlowTrace trace,
            String task
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

    public record AiCodingContextResponse(
            String content,
            String model
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

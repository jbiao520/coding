package com.example.codeobserver.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codeobserver.model.WorkspaceModels.ProjectDetail;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JavaProjectAnalyzerFlowTest {
    @Test
    void analyzerDoesNotPretendStaticRulesAreRecommendedFlows() {
        Path raftRoot = Path.of("..", "..", "Raft").toAbsolutePath().normalize();
        assertThat(Files.isDirectory(raftRoot)).isTrue();

        JavaProjectAnalyzer analyzer = new JavaProjectAnalyzer(new ConceptExtractor());
        ProjectDetail detail = analyzer.analyze(raftRoot, "raft-test");

        assertThat(detail.flows()).isEmpty();
        assertThat(detail.readingPath()).isNotEmpty();
        assertThat(detail.graphNodes()).isNotEmpty();
    }
}

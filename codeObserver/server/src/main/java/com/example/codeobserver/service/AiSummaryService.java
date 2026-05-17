package com.example.codeobserver.service;

import static com.example.codeobserver.model.WorkspaceModels.AiSummaryRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiSummaryResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphNode;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextResponse;
import static com.example.codeobserver.model.WorkspaceModels.ClassInfo;
import static com.example.codeobserver.model.WorkspaceModels.FlowTrace;
import static com.example.codeobserver.model.WorkspaceModels.GraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.GraphNode;
import static com.example.codeobserver.model.WorkspaceModels.MethodInfo;
import static com.example.codeobserver.model.WorkspaceModels.ProjectDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.codeobserver.config.LearningFlowAiProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

@Service
public class AiSummaryService {
    private static final int MAX_SNIPPET_LINES = 90;
    private static final int MAX_AI_GRAPH_NODES = 8;

    private final WorkspaceService workspaceService;
    private final LearningFlowAiProperties properties;
    private final ObjectMapper objectMapper;

    public AiSummaryService(
            WorkspaceService workspaceService,
            LearningFlowAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiSummaryResponse summarize(AiSummaryRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.model();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        String content = chatClient.prompt()
                .system(systemPrompt())
                .user(buildUserPrompt(detail, request))
                .call()
                .content();
        return new AiSummaryResponse(content == null ? "" : content.trim(), model);
    }

    public Flux<String> streamSummary(AiSummaryRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.model();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        return chatClient.prompt()
                .system(systemPrompt())
                .user(buildUserPrompt(detail, request))
                .stream()
                .content()
                .filter(chunk -> chunk != null);
    }

    public AiCallGraphResponse generateCallGraph(AiCallGraphRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.model();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        String content = chatClient.prompt()
                .system(callGraphSystemPrompt())
                .user(buildCallGraphUserPrompt(detail, request))
                .call()
                .content();
        AiCallGraphResponse parsed = parseCallGraphResponse(content);
        return normalizeCallGraphResponse(detail, request, parsed, model);
    }

    public AiCodingContextResponse generateCodingContext(AiCodingContextRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.contextModel();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        String content = chatClient.prompt()
                .system(codingContextSystemPrompt())
                .user(buildCodingContextUserPrompt(detail, request))
                .call()
                .content();
        return new AiCodingContextResponse(content == null ? "" : content.trim(), model);
    }

    public Flux<String> streamCodingContext(AiCodingContextRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.contextModel();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        return chatClient.prompt()
                .system(codingContextSystemPrompt())
                .user(buildCodingContextUserPrompt(detail, request))
                .stream()
                .content()
                .filter(chunk -> chunk != null);
    }

    public String modelName() {
        return properties.model();
    }

    public String contextModelName() {
        return properties.contextModel();
    }

    private DeepSeekChatModel chatModel(String apiKey, String model) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(properties.url())
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder())
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .maxTokens(properties.maxTokens())
                .temperature(properties.temperature())
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(options)
                .build();
    }

    private RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    private String apiKey() {
        Optional<String> fromFile = readApiKeyFile();
        if (fromFile.isPresent()) {
            return fromFile.get();
        }
        String fromEnv = System.getenv("LEARNING_FLOW_AI_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        if (properties.requireApiKey()) {
            throw new IllegalStateException("AI API key not found. Configure LEARNING_FLOW_AI_API_KEY or "
                    + properties.apiKeyFile());
        }
        return "";
    }

    private Optional<String> readApiKeyFile() {
        Path path = expandHome(properties.apiKeyFile());
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String key = Files.readString(path, StandardCharsets.UTF_8).trim();
            return key.isBlank() ? Optional.empty() : Optional.of(key);
        } catch (IOException ex) {
            if (properties.requireApiKey()) {
                throw new IllegalStateException("Cannot read AI API key file: " + path, ex);
            }
            return Optional.empty();
        }
    }

    private Path expandHome(String value) {
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private String systemPrompt() {
        return """
                你是 Code Observer 的源码讲解助手。你只能根据用户提供的项目结构、调用链和源码片段回答。
                用中文，面向正在学习 Java 中间件/系统设计代码的人解释。回答要具体、短而有层次。
                如果用户没有提具体问题，就解释这条 flow 的业务目的、执行步骤、关键状态变化、最值得阅读的代码。
                如果用户提了问题，优先回答问题；缺少上下文时明确说看不到，不要编造。
                """;
    }

    private String callGraphSystemPrompt() {
        return """
                你是 Code Observer 的 AI 调用图生成器。你只能根据用户提供的候选节点、候选边和源码片段生成图。
                目标是把冷冰冰的代码调用图整理成适合人理解的学习图：保留关键业务/状态转换步骤，省略 getter、setter、纯字段读取、日志、样板包装和不影响理解的细枝末节。
                必须只返回 JSON，不要 Markdown，不要代码块，不要额外说明。
                JSON 结构：
                {
                  "title": "不超过 24 个中文字符的图标题",
                  "summary": "一句话说明这张图帮用户理解什么",
                  "nodes": [
                    {
                      "id": "必须完全使用候选节点 id",
                      "label": "更适合人读的短名称",
                      "subtitle": "这个节点在流程里的角色",
                      "explanation": "一句具体解释，说明它做了什么、改变了什么状态或为什么重要",
                      "importance": "core 或 supporting"
                    }
                  ],
                  "edges": [
                    {
                      "source": "必须完全使用候选节点 id",
                      "target": "必须完全使用候选节点 id",
                      "label": "短动作短语",
                      "explanation": "一句话解释这次跳转传递了什么意图或状态"
                    }
                  ]
                }
                限制：nodes 最多 8 个，edges 只连接返回的节点。解释必须具体，不要写“调用下一个方法”这种空话。
                """;
    }

    private String codingContextSystemPrompt() {
        return """
                你是 Code Observer 的 AI 编程上下文压缩器。你的输出会被用户粘贴给另一个更强但更贵的代码模型作为参考上下文。
                只能根据用户提供的项目结构、选中调用链、符号元数据和源码片段生成内容，不要编造未出现的文件、方法、配置或测试。
                用中文写，保留类名、方法名、字段名、路径和行号等英文代码标识符。
                目标是把输入材料压缩成事实清单。只反映已给出的事实，不做判断、不提建议、不推断意图、不评价重要性。
                输出 Markdown，直接从标题开始，不要寒暄，不要包代码块，不要写完整源码。
                必须包含这些小节：
                # Coding Context
                ## User Task
                ## Project Facts
                ## Selected Call Chain Facts
                ## Symbol Facts
                ## Direct Call Facts
                ## Source Snippet Facts
                每条内容都必须能对应到输入中的项目元数据、调用链、符号元数据、直接调用边或源码片段。
                不要输出“建议、应该、最好、风险、可能、关键、重要、优先、注意、需要”这类判断性或指导性表述。
                如果某个小节没有输入事实，就写“未提供”。
                """;
    }

    private String buildUserPrompt(ProjectDetail detail, AiSummaryRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("构建类型：").append(detail.summary().buildType()).append('\n');
        prompt.append("统计：")
                .append(detail.summary().javaFileCount()).append(" files, ")
                .append(detail.summary().classCount()).append(" classes, ")
                .append(detail.summary().methodCount()).append(" methods\n");
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        FlowTrace trace = request.trace();
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append("当前 flow：").append(trace.title()).append('\n');
            prompt.append("前端摘要：").append(trace.summary()).append("\n");
            prompt.append("flow 步骤：\n");
            int index = 1;
            for (String nodeId : trace.nodeIds()) {
                prompt.append(index++).append(". ").append(describeNode(detail, nodeId)).append('\n');
            }
            prompt.append('\n');
        }

        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            prompt.append("当前选中节点：").append(describeNode(detail, request.selectedNodeId())).append("\n\n");
        }

        List<String> snippets = relevantSnippets(detail, request.selectedNodeId(), request.trace());
        if (!snippets.isEmpty()) {
            prompt.append("相关源码片段：\n");
            for (String snippet : snippets) {
                prompt.append(snippet).append("\n\n");
            }
        }

        if (question.isBlank()) {
            prompt.append("请解释当前 flow，并补充 2 个适合继续追问的问题。");
        } else {
            prompt.append("用户问题：").append(question);
        }
        return prompt.toString();
    }

    private String buildCallGraphUserPrompt(ProjectDetail detail, AiCallGraphRequest request) {
        List<String> candidateIds = candidateNodeIds(detail, request);
        Set<String> candidateIdSet = new LinkedHashSet<>(candidateIds);
        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        FlowTrace trace = request.trace();
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append("当前用户选择的调用链：").append(trace.title()).append('\n');
            prompt.append("前端摘要：").append(trace.summary()).append("\n\n");
        }
        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            prompt.append("当前选中节点：").append(describeNode(detail, request.selectedNodeId())).append("\n\n");
        }

        prompt.append("候选节点（只能从这些 id 中挑选）：\n");
        int index = 1;
        for (String nodeId : candidateIds) {
            prompt.append(index++).append(". id=").append(nodeId).append('\n');
            prompt.append("   ").append(describeNode(detail, nodeId)).append('\n');
            MethodInfo method = findMethod(detail, nodeId).orElse(null);
            if (method != null) {
                prompt.append("   concepts: ").append(shortJoin(method.concepts())).append('\n');
                prompt.append("   direct calls: ").append(shortJoin(method.calls())).append('\n');
            }
        }
        prompt.append('\n');

        prompt.append("候选边（只能连接这些节点，允许省略不重要的边）：\n");
        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> candidateIdSet.contains(edge.source()) && candidateIdSet.contains(edge.target()))
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .forEach(edge -> prompt.append("- ")
                        .append(edge.source()).append(" -> ").append(edge.target())
                        .append(" | ").append(edge.label()).append('\n'));
        prompt.append('\n');

        List<String> snippets = relevantSnippets(detail, request.selectedNodeId(), request.trace());
        if (!snippets.isEmpty()) {
            prompt.append("相关源码片段：\n");
            for (String snippet : snippets) {
                prompt.append(snippet).append("\n\n");
            }
        }

        prompt.append("""
                请生成 AI 调用图：挑出最值得理解的关键节点，必要时重命名 label，让 subtitle/explanation 解释清楚这个节点的职责、状态变化和它对整条链路的意义。
                不要返回候选节点之外的 id。
                """);
        return prompt.toString();
    }

    private String buildCodingContextUserPrompt(ProjectDetail detail, AiCodingContextRequest request) {
        String task = request.task() == null ? "" : request.task().trim();
        List<String> contextNodeIds = codingContextNodeIds(detail, request);
        Set<String> contextNodeIdSet = new LinkedHashSet<>(contextNodeIds);

        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("构建类型：").append(detail.summary().buildType()).append('\n');
        prompt.append("统计：")
                .append(detail.summary().javaFileCount()).append(" files, ")
                .append(detail.summary().classCount()).append(" classes, ")
                .append(detail.summary().methodCount()).append(" methods\n");
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        if (!task.isBlank()) {
            prompt.append("用户准备交给代码模型的任务/目标：").append(task).append("\n\n");
        } else {
            prompt.append("用户未填写具体任务。最终 context 的 User Task 小节写“未提供”。\n\n");
        }

        FlowTrace trace = request.trace();
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append("当前用户选中的调用链：").append(trace.title()).append('\n');
            prompt.append("前端摘要：").append(trace.summary()).append("\n");
            prompt.append("链路步骤：\n");
            int index = 1;
            for (String nodeId : trace.nodeIds()) {
                prompt.append(index++).append(". ").append(describeNode(detail, nodeId)).append('\n');
            }
            prompt.append('\n');
        }

        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            prompt.append("当前选中节点：").append(describeNode(detail, request.selectedNodeId())).append("\n\n");
        }

        prompt.append("符号事实（可在输出中引用路径和行号）：\n");
        int index = 1;
        for (String nodeId : contextNodeIds) {
            Optional<GraphNode> graphNode = detail.graphNodes().stream()
                    .filter(node -> node.id().equals(nodeId))
                    .findFirst();
            if (graphNode.isEmpty()) {
                continue;
            }
            GraphNode node = graphNode.get();
            prompt.append(index++).append(". id=").append(node.id()).append('\n');
            prompt.append("   label=").append(readableNodeLabel(detail, node))
                    .append(" | type=").append(node.type())
                    .append(" | file=").append(node.filePath())
                    .append(" | line=").append(node.line())
                    .append('\n');
            MethodInfo method = findMethod(detail, node.id()).orElse(null);
            if (method != null) {
                prompt.append("   signature=").append(method.signature())
                        .append(" -> ").append(method.returnType()).append('\n');
                prompt.append("   concepts=").append(shortJoin(method.concepts())).append('\n');
                prompt.append("   direct calls=").append(shortJoin(method.calls())).append('\n');
                prompt.append("   creates=").append(shortJoin(method.creates())).append('\n');
            }
        }
        prompt.append('\n');

        prompt.append("这些符号之间的直接调用边：\n");
        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> contextNodeIdSet.contains(edge.source()) && contextNodeIdSet.contains(edge.target()))
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .forEach(edge -> prompt.append("- ")
                        .append(edge.source()).append(" -> ").append(edge.target())
                        .append(" | ").append(edge.label()).append('\n'));
        prompt.append('\n');

        List<String> snippets = relevantSnippets(detail, request.selectedNodeId(), request.trace());
        if (!snippets.isEmpty()) {
            prompt.append("源码片段事实（最终 context 只能摘录或改写这些已给出的事实，不要大段复制源码）：\n");
            for (String snippet : snippets) {
                prompt.append(snippet).append("\n\n");
            }
        }

        prompt.append("""
                请生成可以粘贴给代码模型的事实型压缩上下文。要求：
                1. 只整理输入中已经出现的项目事实、调用链事实、符号事实、直接调用边事实和源码片段事实。
                2. 不判断哪些文件或方法更重要，不给实现方案，不给测试建议，不写风险或缺口。
                3. 不推断未明示的状态变化；只有源码片段或元数据中直接出现的字段、返回值、调用、创建关系才可以写。
                4. 不使用“建议、应该、最好、风险、可能、关键、重要、优先、注意、需要”等判断性或指导性词。
                5. 输出要短，避免把源码逐行搬运过去。
                """);
        return prompt.toString();
    }

    private String describeNode(ProjectDetail detail, String nodeId) {
        Optional<GraphNode> graphNode = detail.graphNodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst();
        Optional<ClassInfo> classInfo = detail.classes().stream()
                .filter(item -> item.id().equals(nodeId))
                .findFirst();
        if (classInfo.isPresent()) {
            ClassInfo item = classInfo.get();
            return item.kind() + " " + item.qualifiedName() + " (" + item.beginLine() + "-" + item.endLine() + ")";
        }
        for (ClassInfo owner : detail.classes()) {
            Optional<MethodInfo> method = owner.methods().stream()
                    .filter(item -> item.id().equals(nodeId))
                    .findFirst();
            if (method.isPresent()) {
                MethodInfo item = method.get();
                return owner.name() + "." + item.signature()
                        + " -> " + item.returnType()
                        + " | calls: " + shortJoin(item.calls());
            }
        }
        return graphNode.map(node -> node.type() + " " + node.label()).orElse(nodeId);
    }

    private String shortJoin(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values.stream().limit(8).toList());
    }

    private List<String> candidateNodeIds(ProjectDetail detail, AiCallGraphRequest request) {
        Set<String> nodeIds = new LinkedHashSet<>();
        FlowTrace trace = request.trace();
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            nodeIds.addAll(trace.nodeIds());
        }
        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            nodeIds.add(request.selectedNodeId());
            addNeighborhood(detail, request.selectedNodeId(), nodeIds);
        }
        if (nodeIds.isEmpty()) {
            detail.readingPath().stream()
                    .limit(3)
                    .map(step -> step.targetNodeId())
                    .forEach(nodeIds::add);
        }
        if (nodeIds.isEmpty()) {
            detail.graphNodes().stream()
                    .filter(node -> "method".equals(node.type()))
                    .limit(8)
                    .map(GraphNode::id)
                    .forEach(nodeIds::add);
        }
        return nodeIds.stream()
                .filter(nodeId -> detail.graphNodes().stream().anyMatch(node -> node.id().equals(nodeId)))
                .limit(16)
                .toList();
    }

    private List<String> codingContextNodeIds(ProjectDetail detail, AiCodingContextRequest request) {
        Set<String> nodeIds = new LinkedHashSet<>();
        FlowTrace trace = request.trace();
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            nodeIds.addAll(trace.nodeIds());
        }
        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            nodeIds.add(request.selectedNodeId());
            addNeighborhood(detail, request.selectedNodeId(), nodeIds);
        }
        if (nodeIds.isEmpty()) {
            detail.readingPath().stream()
                    .limit(5)
                    .map(step -> step.targetNodeId())
                    .forEach(nodeIds::add);
        }
        if (nodeIds.isEmpty()) {
            detail.graphNodes().stream()
                    .filter(node -> "method".equals(node.type()))
                    .limit(12)
                    .map(GraphNode::id)
                    .forEach(nodeIds::add);
        }
        return nodeIds.stream()
                .filter(nodeId -> detail.graphNodes().stream().anyMatch(node -> node.id().equals(nodeId)))
                .limit(18)
                .toList();
    }

    private void addNeighborhood(ProjectDetail detail, String selectedNodeId, Set<String> nodeIds) {
        Optional<GraphNode> selected = detail.graphNodes().stream()
                .filter(node -> node.id().equals(selectedNodeId))
                .findFirst();
        if (selected.isPresent() && "class".equals(selected.get().type())) {
            detail.classes().stream()
                    .filter(classInfo -> classInfo.id().equals(selectedNodeId))
                    .findFirst()
                    .ifPresent(classInfo -> classInfo.methods().stream()
                            .limit(10)
                            .map(MethodInfo::id)
                            .forEach(nodeIds::add));
            return;
        }
        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> edge.source().equals(selectedNodeId) || edge.target().equals(selectedNodeId))
                .limit(12)
                .forEach(edge -> {
                    nodeIds.add(edge.source());
                    nodeIds.add(edge.target());
                });
    }

    private List<String> relevantSnippets(ProjectDetail detail, String selectedNodeId, FlowTrace trace) {
        Set<String> nodeIds = new LinkedHashSet<>();
        if (trace != null && trace.nodeIds() != null) {
            nodeIds.addAll(trace.nodeIds());
        }
        if (selectedNodeId != null && !selectedNodeId.isBlank()) {
            nodeIds.add(selectedNodeId);
        }

        List<String> snippets = new ArrayList<>();
        for (String nodeId : nodeIds) {
            findSnippet(detail, nodeId).ifPresent(snippets::add);
            if (snippets.size() >= 5) {
                break;
            }
        }
        return snippets;
    }

    private AiCallGraphResponse parseCallGraphResponse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI did not return a call graph.");
        }
        String json = extractJsonObject(content.trim());
        try {
            return objectMapper.readValue(json, AiCallGraphResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AI returned a call graph that could not be parsed.", ex);
        }
    }

    private String extractJsonObject(String value) {
        String next = value;
        if (next.startsWith("```")) {
            int firstLineBreak = next.indexOf('\n');
            int lastFence = next.lastIndexOf("```");
            if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
                next = next.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        int start = next.indexOf('{');
        int end = next.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return next.substring(start, end + 1);
        }
        return next;
    }

    private AiCallGraphResponse normalizeCallGraphResponse(
            ProjectDetail detail,
            AiCallGraphRequest request,
            AiCallGraphResponse raw,
            String model
    ) {
        Map<String, GraphNode> graphNodes = new HashMap<>();
        for (GraphNode node : detail.graphNodes()) {
            graphNodes.put(node.id(), node);
        }

        List<AiCallGraphNode> nodes = new ArrayList<>();
        Set<String> acceptedNodeIds = new LinkedHashSet<>();
        List<AiCallGraphNode> rawNodes = raw.nodes() == null ? List.of() : raw.nodes();
        for (AiCallGraphNode rawNode : rawNodes) {
            if (rawNode.id() == null || !graphNodes.containsKey(rawNode.id()) || !acceptedNodeIds.add(rawNode.id())) {
                continue;
            }
            GraphNode source = graphNodes.get(rawNode.id());
            nodes.add(new AiCallGraphNode(
                    source.id(),
                    defaultIfBlank(rawNode.label(), readableNodeLabel(detail, source)),
                    defaultIfBlank(rawNode.subtitle(), graphNodeRole(detail, source)),
                    defaultIfBlank(rawNode.explanation(), "这个节点是当前链路中的关键步骤。"),
                    normalizeImportance(rawNode.importance()),
                    source.filePath(),
                    source.line()));
            if (nodes.size() >= MAX_AI_GRAPH_NODES) {
                break;
            }
        }

        if (nodes.isEmpty()) {
            nodes.addAll(fallbackCallGraphNodes(detail, request));
            acceptedNodeIds.addAll(nodes.stream().map(AiCallGraphNode::id).toList());
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("AI call graph has no valid nodes.");
        }

        Set<String> nodeIdSet = new LinkedHashSet<>(nodes.stream().map(AiCallGraphNode::id).toList());
        List<AiCallGraphEdge> edges = new ArrayList<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        List<AiCallGraphEdge> rawEdges = raw.edges() == null ? List.of() : raw.edges();
        for (AiCallGraphEdge rawEdge : rawEdges) {
            if (rawEdge.source() == null || rawEdge.target() == null) {
                continue;
            }
            if (!nodeIdSet.contains(rawEdge.source()) || !nodeIdSet.contains(rawEdge.target())) {
                continue;
            }
            String id = defaultIfBlank(rawEdge.id(), "ai:" + rawEdge.source() + "->" + rawEdge.target());
            if (!edgeIds.add(id)) {
                continue;
            }
            edges.add(new AiCallGraphEdge(
                    id,
                    rawEdge.source(),
                    rawEdge.target(),
                    defaultIfBlank(rawEdge.label(), "进入下一步"),
                    defaultIfBlank(rawEdge.explanation(), "这里把流程推进到下一个关键步骤。")));
        }
        if (edges.isEmpty() && nodes.size() > 1) {
            edges.addAll(fallbackCallGraphEdges(detail, nodes, edgeIds));
        }

        return new AiCallGraphResponse(
                defaultIfBlank(raw.title(), "AI 精简调用图"),
                defaultIfBlank(raw.summary(), "AI 已保留当前链路中最影响理解的调用步骤。"),
                nodes,
                edges,
                model);
    }

    private List<AiCallGraphNode> fallbackCallGraphNodes(ProjectDetail detail, AiCallGraphRequest request) {
        return candidateNodeIds(detail, request).stream()
                .map(nodeId -> detail.graphNodes().stream()
                        .filter(node -> node.id().equals(nodeId))
                        .findFirst()
                        .orElse(null))
                .filter(node -> node != null && "method".equals(node.type()))
                .limit(MAX_AI_GRAPH_NODES)
                .map(node -> new AiCallGraphNode(
                        node.id(),
                        readableNodeLabel(detail, node),
                        graphNodeRole(detail, node),
                        "这是当前上下文里最值得先看的调用节点。",
                        "core",
                        node.filePath(),
                        node.line()))
                .toList();
    }

    private List<AiCallGraphEdge> fallbackCallGraphEdges(
            ProjectDetail detail,
            List<AiCallGraphNode> nodes,
            Set<String> edgeIds
    ) {
        Set<String> nodeIds = new LinkedHashSet<>(nodes.stream().map(AiCallGraphNode::id).toList());
        List<AiCallGraphEdge> edges = new ArrayList<>();
        for (GraphEdge edge : detail.graphEdges()) {
            if (!"CALLS".equals(edge.type()) || !nodeIds.contains(edge.source()) || !nodeIds.contains(edge.target())) {
                continue;
            }
            String id = "ai:" + edge.source() + "->" + edge.target();
            if (edgeIds.add(id)) {
                edges.add(new AiCallGraphEdge(id, edge.source(), edge.target(), edge.label(), "源码中的直接调用关系。"));
            }
        }
        return edges;
    }

    private String normalizeImportance(String importance) {
        if ("supporting".equalsIgnoreCase(importance)) {
            return "supporting";
        }
        return "core";
    }

    private String readableNodeLabel(ProjectDetail detail, GraphNode node) {
        String owner = ownerClassName(detail, node.id());
        return owner.isBlank() ? node.label() : owner + "." + node.label();
    }

    private String graphNodeRole(ProjectDetail detail, GraphNode node) {
        if ("class".equals(node.type())) {
            return localizedKind(String.valueOf(node.data().getOrDefault("kind", "class")));
        }
        Optional<MethodInfo> method = findMethod(detail, node.id());
        if (method.isPresent() && !method.get().returnType().isBlank()) {
            return method.get().returnType();
        }
        return "方法";
    }

    private String localizedKind(String kind) {
        return switch (kind) {
            case "record" -> "记录";
            case "enum" -> "枚举";
            case "interface" -> "接口";
            default -> "类";
        };
    }

    private String ownerClassName(ProjectDetail detail, String methodId) {
        return detail.classes().stream()
                .filter(classInfo -> classInfo.methods().stream().anyMatch(method -> method.id().equals(methodId)))
                .map(ClassInfo::name)
                .findFirst()
                .orElse("");
    }

    private Optional<MethodInfo> findMethod(ProjectDetail detail, String nodeId) {
        for (ClassInfo classInfo : detail.classes()) {
            Optional<MethodInfo> method = classInfo.methods().stream()
                    .filter(item -> item.id().equals(nodeId))
                    .findFirst();
            if (method.isPresent()) {
                return method;
            }
        }
        return Optional.empty();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Optional<String> findSnippet(ProjectDetail detail, String nodeId) {
        for (ClassInfo classInfo : detail.classes()) {
            if (classInfo.id().equals(nodeId)) {
                return readSnippet(classInfo.name(), classInfo.filePath(), classInfo.beginLine(), classInfo.endLine());
            }
            for (MethodInfo method : classInfo.methods()) {
                if (method.id().equals(nodeId)) {
                    return readSnippet(classInfo.name() + "." + method.name(), classInfo.filePath(), method.beginLine(), method.endLine());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> readSnippet(String title, String filePath, int beginLine, int endLine) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(1, beginLine);
            int end = Math.min(lines.size(), Math.max(start, endLine));
            int cappedEnd = Math.min(end, start + MAX_SNIPPET_LINES - 1);
            StringBuilder snippet = new StringBuilder();
            snippet.append("### ").append(title)
                    .append(" @ ").append(path.getFileName())
                    .append(":").append(start).append("-").append(cappedEnd)
                    .append('\n');
            snippet.append("```java\n");
            for (int line = start; line <= cappedEnd; line++) {
                snippet.append(line).append(": ").append(lines.get(line - 1)).append('\n');
            }
            snippet.append("```");
            return Optional.of(snippet.toString());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}

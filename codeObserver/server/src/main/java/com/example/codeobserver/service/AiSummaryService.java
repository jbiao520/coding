package com.example.codeobserver.service;

import static com.example.codeobserver.model.WorkspaceModels.AiSummaryRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiSummaryResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphNode;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCallGraphResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiCodingContextResponse;
import static com.example.codeobserver.model.WorkspaceModels.AiFlowRecommendationRequest;
import static com.example.codeobserver.model.WorkspaceModels.AiFlowRecommendationResponse;
import static com.example.codeobserver.model.WorkspaceModels.CallSiteInfo;
import static com.example.codeobserver.model.WorkspaceModels.ClassInfo;
import static com.example.codeobserver.model.WorkspaceModels.FlowInfo;
import static com.example.codeobserver.model.WorkspaceModels.FlowStep;
import static com.example.codeobserver.model.WorkspaceModels.FlowTrace;
import static com.example.codeobserver.model.WorkspaceModels.GraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.GraphNode;
import static com.example.codeobserver.model.WorkspaceModels.MethodInfo;
import static com.example.codeobserver.model.WorkspaceModels.PinnedSourceSnippet;
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
import com.fasterxml.jackson.databind.JsonNode;
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
    private static final int MAX_CONTEXT_SYMBOLS = 14;
    private static final int MAX_CONTEXT_CLASS_METHODS = 8;
    private static final int MAX_CONTEXT_DIRECT_EDGES = 32;
    private static final int MAX_CONTEXT_SNIPPETS = 6;

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

    public AiFlowRecommendationResponse recommendFlows(AiFlowRecommendationRequest request) {
        if (!"deepseek".equalsIgnoreCase(properties.mode())) {
            throw new IllegalStateException("Unsupported learning-flow.ai.mode: " + properties.mode());
        }
        ProjectDetail detail = workspaceService.project(request.projectId(), request.root())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        String apiKey = apiKey();

        String model = properties.model();
        ChatClient chatClient = ChatClient.create(chatModel(apiKey, model));
        String content = chatClient.prompt()
                .system(flowRecommendationSystemPrompt())
                .user(buildFlowRecommendationUserPrompt(detail, request))
                .call()
                .content();
        AiFlowDraftResponse parsed = parseFlowRecommendationResponse(content);
        return normalizeFlowRecommendationResponse(detail, request, parsed, model);
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

    private String flowRecommendationSystemPrompt() {
        return """
                你是 Code Observer 的源码阅读 Flow 规划器。你只能根据用户提供的候选节点、候选边、符号事实和源码片段生成推荐 Flow。
                Flow 是一个“为了理解某个具体行为而阅读的代码路径”，不是完整调用树，也不是按类排序。
                必须只返回 JSON，不要 Markdown，不要代码块，不要额外说明。
                JSON 结构：
                {
                  "flows": [
                    {
                      "title": "不超过 24 个中文字符的 Flow 名称",
                      "summary": "一句话说明这个 Flow 帮用户理解什么行为",
                      "nodeIds": ["必须完全使用候选节点 id，按阅读顺序排列"],
                      "tags": ["2-4 个短标签"],
                      "steps": [
                        {
                          "nodeId": "必须属于 nodeIds",
                          "description": "一句话说明这一步做了什么、读写了什么状态、为什么在该行为里出现"
                        }
                      ]
                    }
                  ]
                }
                限制：
                - 最多返回 5 条 Flow。
                - 每条 Flow 只保留 3-9 个步骤。
                - 不要把 getter、setter、toString、print、DTO/Request/Response/enum/record 的简单方法作为 Flow 终点。
                - 如果候选信息不足以形成有意义的行为 Flow，返回 {"flows": []}。
                - 不要创造候选节点之外的 id。
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
                ## Selected Flow
                ## Project Facts
                ## Selected Call Chain Facts
                ## Symbol Facts
                ## Direct Call Facts
                ## Source Snippet Facts
                每条内容都必须能对应到输入中的项目元数据、调用链、符号元数据、直接调用边或源码片段。
                Symbol Facts 只展开输入中列出的符号；accessor-like 方法若出现在压缩访问器事实里，只能合并描述，不要逐个扩展成完整符号。
                Direct Call Facts 只整理输入中“直接调用边事实”列出的边；不要从源码片段自行补边。
                不要输出“建议、应该、最好、风险、可能、关键、重要、优先、注意、需要”这类判断性或指导性表述。
                如果某个小节没有输入事实，就写“未提供”。
                """;
    }

    private String buildUserPrompt(ProjectDetail detail, AiSummaryRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        FlowInfo flow = selectedFlow(detail, request.flowId()).orElse(null);
        FlowTrace trace = flowTrace(flow).orElse(request.trace());
        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("构建类型：").append(detail.summary().buildType()).append('\n');
        prompt.append("统计：")
                .append(detail.summary().javaFileCount()).append(" files, ")
                .append(detail.summary().classCount()).append(" classes, ")
                .append(detail.summary().methodCount()).append(" methods\n");
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append(flow == null ? "当前 flow：" : "当前后端 Flow：").append(trace.title()).append('\n');
            prompt.append("摘要：").append(trace.summary()).append("\n");
            appendFlowStepFacts(prompt, flow);
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

        List<String> snippets = relevantSnippets(detail, request.selectedNodeId(), trace);
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
        FlowInfo flow = selectedFlow(detail, request.flowId()).orElse(null);
        FlowTrace trace = flowTrace(flow).orElse(request.trace());
        List<String> candidateIds = candidateNodeIds(detail, request);
        Set<String> candidateIdSet = new LinkedHashSet<>(candidateIds);
        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append(flow == null ? "当前用户选择的调用链：" : "当前用户选择的后端 Flow：").append(trace.title()).append('\n');
            prompt.append("摘要：").append(trace.summary()).append("\n");
            appendFlowStepFacts(prompt, flow);
            prompt.append('\n');
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
                prompt.append("   call sites: ").append(shortJoin(callSiteFacts(method))).append('\n');
                prompt.append("   reads fields: ").append(shortJoin(method.readsFields())).append('\n');
                prompt.append("   writes fields: ").append(shortJoin(method.writesFields())).append('\n');
                prompt.append("   branches: ").append(shortJoin(method.branchFacts())).append('\n');
                prompt.append("   exceptions: ").append(shortJoin(method.exceptionFacts())).append('\n');
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
                        .append(" | ").append(edgeFact(edge)).append('\n'));
        prompt.append('\n');

        List<String> snippets = relevantSnippets(detail, request.selectedNodeId(), trace);
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

    private String buildFlowRecommendationUserPrompt(ProjectDetail detail, AiFlowRecommendationRequest request) {
        List<String> candidateIds = flowCandidateNodeIds(detail, request.selectedNodeId());
        Set<String> candidateIdSet = new LinkedHashSet<>(candidateIds);
        String instruction = request.instruction() == null ? "" : request.instruction().trim();

        StringBuilder prompt = new StringBuilder();
        prompt.append("项目：").append(detail.summary().name()).append('\n');
        prompt.append("构建类型：").append(detail.summary().buildType()).append('\n');
        prompt.append("项目概念：").append(String.join("、", detail.summary().concepts())).append("\n\n");

        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            prompt.append("用户当前选中：").append(describeNode(detail, request.selectedNodeId())).append("\n\n");
        } else {
            prompt.append("用户当前选中：未提供。请基于项目入口和高价值行为节点判断。\n\n");
        }
        if (!instruction.isBlank()) {
            prompt.append("用户补充目标：").append(instruction).append("\n\n");
        }

        prompt.append("候选节点（只能使用这些 id 组成 Flow）：\n");
        int index = 1;
        for (String nodeId : candidateIds) {
            prompt.append(index++).append(". id=").append(nodeId).append('\n');
            prompt.append("   ").append(describeNode(detail, nodeId)).append('\n');
            MethodInfo method = findMethod(detail, nodeId).orElse(null);
            if (method != null) {
                prompt.append("   concepts=").append(shortJoin(method.concepts())).append('\n');
                prompt.append("   direct calls=").append(shortJoin(method.calls())).append('\n');
                prompt.append("   call sites=").append(shortJoin(callSiteFacts(method))).append('\n');
                prompt.append("   creates=").append(shortJoin(method.creates())).append('\n');
                prompt.append("   reads fields=").append(shortJoin(method.readsFields())).append('\n');
                prompt.append("   writes fields=").append(shortJoin(method.writesFields())).append('\n');
                prompt.append("   branches=").append(shortJoin(method.branchFacts())).append('\n');
                prompt.append("   exceptions=").append(shortJoin(method.exceptionFacts())).append('\n');
            }
        }
        prompt.append('\n');

        prompt.append("候选调用边：\n");
        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> candidateIdSet.contains(edge.source()) && candidateIdSet.contains(edge.target()))
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .limit(80)
                .forEach(edge -> prompt.append("- ")
                        .append(edge.source()).append(" -> ").append(edge.target())
                        .append(" | ").append(edgeFact(edge)).append('\n'));
        prompt.append('\n');

        List<String> snippets = relevantCodingSnippets(detail, request.selectedNodeId(), null, candidateIds);
        if (!snippets.isEmpty()) {
            prompt.append("相关源码片段：\n");
            for (String snippet : snippets) {
                prompt.append(snippet).append("\n\n");
            }
        }

        prompt.append("""
                请基于用户选中的类/方法生成推荐阅读 Flow。
                优先选择能解释业务行为、状态变化、跨对象协作的路径；忽略纯打印、简单 accessor、数据对象构造和异常包装路径。
                如果选中的类只是数据结构或上下文不足，就返回空 flows。
                """);
        return prompt.toString();
    }

    private String buildCodingContextUserPrompt(ProjectDetail detail, AiCodingContextRequest request) {
        String task = request.task() == null ? "" : request.task().trim();
        FlowInfo flow = selectedFlow(detail, request.flowId()).orElse(null);
        FlowTrace trace = flowTrace(flow).orElse(request.trace());
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

        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            prompt.append(flow == null ? "当前用户选中的调用链：" : "当前用户选中的后端 Flow：").append(trace.title()).append('\n');
            prompt.append("摘要：").append(trace.summary()).append("\n");
            appendFlowStepFacts(prompt, flow);
            prompt.append("链路步骤：\n");
            int index = 1;
            for (String nodeId : trace.nodeIds()) {
                prompt.append(index++).append(". ").append(describeNode(detail, nodeId)).append('\n');
            }
            prompt.append('\n');
        } else {
            prompt.append("当前用户选中的 Flow/调用链：未提供\n\n");
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
                prompt.append("   call sites=").append(shortJoin(callSiteFacts(method))).append('\n');
                prompt.append("   creates=").append(shortJoin(method.creates())).append('\n');
                prompt.append("   reads fields=").append(shortJoin(method.readsFields())).append('\n');
                prompt.append("   writes fields=").append(shortJoin(method.writesFields())).append('\n');
                prompt.append("   branches=").append(shortJoin(method.branchFacts())).append('\n');
                prompt.append("   exceptions=").append(shortJoin(method.exceptionFacts())).append('\n');
            }
        }
        prompt.append('\n');

        List<String> accessorFacts = compressedAccessorFacts(detail, request, contextNodeIdSet);
        prompt.append("压缩访问器事实（不要在 Symbol Facts 中逐个展开这些方法）：\n");
        if (accessorFacts.isEmpty()) {
            prompt.append("- none\n");
        } else {
            accessorFacts.forEach(fact -> prompt.append("- ").append(fact).append('\n'));
        }
        prompt.append('\n');

        prompt.append("直接调用边事实（source 属于符号事实；target 是源码分析解析到的调用目标）：\n");
        List<GraphEdge> directEdges = codingContextDirectEdges(detail, contextNodeIdSet);
        if (directEdges.isEmpty()) {
            prompt.append("- none\n");
        } else {
            directEdges.forEach(edge -> prompt.append("- ")
                    .append(edge.source()).append(" -> ").append(edge.target())
                    .append(" | ").append(edgeFact(edge)).append('\n'));
        }
        prompt.append('\n');

        prompt.append("符号事实范围说明：Symbol Facts 来自选中调用链、当前选中节点、当前选中类的非 accessor 方法或当前选中方法的一跳调用关系。\n");
        prompt.append('\n');

        List<String> pinnedSnippetFacts = pinnedSnippetFacts(request.pinnedSnippets());
        if (!pinnedSnippetFacts.isEmpty()) {
            prompt.append("用户固定源码片段事实（显式选择，独立于自动选取片段）：\n");
            for (String snippet : pinnedSnippetFacts) {
                prompt.append(snippet).append("\n\n");
            }
        }

        List<String> snippets = relevantCodingSnippets(detail, request.selectedNodeId(), trace, contextNodeIds);
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
                5. 如果没有选中调用链，Selected Call Chain Facts 只能写“未提供”，不要把 Symbol Facts 改写成调用链。
                6. 输出要短，避免把源码逐行搬运过去。
                """);
        return prompt.toString();
    }

    private List<String> pinnedSnippetFacts(List<PinnedSourceSnippet> pinnedSnippets) {
        if (pinnedSnippets == null || pinnedSnippets.isEmpty()) {
            return List.of();
        }
        return pinnedSnippets.stream()
                .filter(snippet -> snippet != null && snippet.source() != null && !snippet.source().isBlank())
                .limit(10)
                .map(snippet -> {
                    Path path = snippet.filePath() == null || snippet.filePath().isBlank()
                            ? Path.of("unknown")
                            : Path.of(snippet.filePath());
                    String title = defaultIfBlank(snippet.label(), "Pinned snippet");
                    StringBuilder builder = new StringBuilder();
                    builder.append("### ").append(title)
                            .append(" @ ").append(path.getFileName())
                            .append(":").append(Math.max(1, snippet.beginLine()))
                            .append("-").append(Math.max(Math.max(1, snippet.beginLine()), snippet.endLine()))
                            .append('\n');
                    builder.append("```java\n");
                    String[] lines = snippet.source().split("\\R", -1);
                    int lineNo = Math.max(1, snippet.beginLine());
                    for (int index = 0; index < Math.min(lines.length, MAX_SNIPPET_LINES); index++) {
                        builder.append(lineNo + index).append(": ").append(lines[index]).append('\n');
                    }
                    builder.append("```");
                    return builder.toString();
                })
                .toList();
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

    private Optional<FlowInfo> selectedFlow(ProjectDetail detail, String flowId) {
        if (flowId == null || flowId.isBlank() || detail.flows() == null) {
            return Optional.empty();
        }
        return detail.flows().stream()
                .filter(flow -> flow.id().equals(flowId))
                .findFirst();
    }

    private Optional<FlowTrace> flowTrace(FlowInfo flow) {
        if (flow == null || flow.nodeIds() == null || flow.nodeIds().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FlowTrace(flow.id(), flow.title(), flow.summary(), flow.nodeIds()));
    }

    private void appendFlowStepFacts(StringBuilder prompt, FlowInfo flow) {
        if (flow == null || flow.steps() == null || flow.steps().isEmpty()) {
            return;
        }
        prompt.append("后端 Flow 步骤事实：\n");
        int index = 1;
        for (var step : flow.steps()) {
            prompt.append(index++).append(". ").append(step.title())
                    .append(" @ ").append(Path.of(step.filePath()).getFileName()).append(":").append(step.line())
                    .append(" | ").append(step.description()).append('\n');
            if (step.stateReads() != null && !step.stateReads().isEmpty()) {
                prompt.append("   reads=").append(shortJoin(step.stateReads())).append('\n');
            }
            if (step.stateWrites() != null && !step.stateWrites().isEmpty()) {
                prompt.append("   writes=").append(shortJoin(step.stateWrites())).append('\n');
            }
        }
    }

    private String shortJoin(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values.stream().limit(8).toList());
    }

    private List<String> callSiteFacts(MethodInfo method) {
        if (method.callSites() == null || method.callSites().isEmpty()) {
            return List.of();
        }
        return method.callSites().stream()
                .limit(10)
                .map(this::callSiteFact)
                .toList();
    }

    private String callSiteFact(CallSiteInfo callSite) {
        String symbol = "CREATE".equals(callSite.kind()) || "constructor".equals(callSite.kind())
                ? "new " + callSite.name()
                : callSite.name() + "()";
        List<String> parts = new ArrayList<>();
        parts.add("line " + callSite.line());
        parts.add(symbol);
        if (callSite.receiverType() != null && !callSite.receiverType().isBlank()) {
            parts.add("receiver " + callSite.receiverType());
        }
        if (callSite.targetOwner() != null && !callSite.targetOwner().isBlank()) {
            parts.add("target " + callSite.targetOwner() + "." + callSite.targetSignature());
        }
        if (Boolean.FALSE.equals(callSite.resolved())) {
            parts.add("unresolved");
        }
        if (Boolean.TRUE.equals(callSite.inLambda())) {
            parts.add("lambda");
        }
        return String.join(" | ", parts);
    }

    private String edgeFact(GraphEdge edge) {
        List<String> parts = new ArrayList<>();
        parts.add(edge.label());
        Object line = edge.data() == null ? null : edge.data().get("line");
        Object receiver = edge.data() == null ? null : edge.data().get("receiverType");
        Object targetSignature = edge.data() == null ? null : edge.data().get("targetSignature");
        Object dispatch = edge.data() == null ? null : edge.data().get("dispatch");
        Object detail = edge.data() == null ? null : edge.data().get("detail");
        if (line != null) {
            parts.add("line " + line);
        }
        if (receiver != null && !String.valueOf(receiver).isBlank()) {
            parts.add("receiver " + receiver);
        }
        if (targetSignature != null && !String.valueOf(targetSignature).isBlank()) {
            parts.add("target " + targetSignature);
        }
        if (dispatch != null && !"direct".equals(String.valueOf(dispatch))) {
            parts.add("dispatch " + dispatch);
        }
        if (targetSignature == null && detail != null && !String.valueOf(detail).isBlank()) {
            parts.add(String.valueOf(detail));
        }
        return String.join(" | ", parts);
    }

    private List<String> candidateNodeIds(ProjectDetail detail, AiCallGraphRequest request) {
        Set<String> nodeIds = new LinkedHashSet<>();
        FlowTrace trace = flowTrace(selectedFlow(detail, request.flowId()).orElse(null)).orElse(request.trace());
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

    private List<String> flowCandidateNodeIds(ProjectDetail detail, String selectedNodeId) {
        Set<String> nodeIds = new LinkedHashSet<>();
        if (selectedNodeId != null && !selectedNodeId.isBlank()) {
            nodeIds.add(selectedNodeId);
            Optional<GraphNode> selected = detail.graphNodes().stream()
                    .filter(node -> node.id().equals(selectedNodeId))
                    .findFirst();
            if (selected.isPresent() && "class".equals(selected.get().type())) {
                detail.classes().stream()
                        .filter(classInfo -> classInfo.id().equals(selectedNodeId))
                        .findFirst()
                        .ifPresent(classInfo -> classInfo.methods().stream()
                                .filter(method -> !isAccessorLikeMethod(method))
                                .limit(18)
                                .map(MethodInfo::id)
                                .forEach(nodeIds::add));
            } else {
                addCodingNeighborhood(detail, selectedNodeId, nodeIds);
            }
        }
        if (nodeIds.isEmpty()) {
            detail.readingPath().stream()
                    .limit(6)
                    .map(step -> step.targetNodeId())
                    .forEach(nodeIds::add);
        }

        List<String> seedIds = new ArrayList<>(nodeIds);
        for (String seedId : seedIds) {
            detail.graphEdges().stream()
                    .filter(edge -> "CALLS".equals(edge.type()))
                    .filter(edge -> edge.source().equals(seedId) || edge.target().equals(seedId))
                    .limit(20)
                    .forEach(edge -> {
                        nodeIds.add(edge.source());
                        nodeIds.add(edge.target());
                    });
        }

        return nodeIds.stream()
                .filter(nodeId -> detail.graphNodes().stream()
                        .anyMatch(node -> node.id().equals(nodeId) && "method".equals(node.type())))
                .filter(nodeId -> !isAccessorLikeMethod(detail, nodeId))
                .limit(40)
                .toList();
    }

    private List<String> codingContextNodeIds(ProjectDetail detail, AiCodingContextRequest request) {
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> explicitNodeIds = new LinkedHashSet<>();
        FlowTrace trace = flowTrace(selectedFlow(detail, request.flowId()).orElse(null)).orElse(request.trace());
        if (trace != null && trace.nodeIds() != null && !trace.nodeIds().isEmpty()) {
            explicitNodeIds.addAll(trace.nodeIds());
            nodeIds.addAll(trace.nodeIds());
        }
        if (request.selectedNodeId() != null && !request.selectedNodeId().isBlank()) {
            explicitNodeIds.add(request.selectedNodeId());
            nodeIds.add(request.selectedNodeId());
            addCodingNeighborhood(detail, request.selectedNodeId(), nodeIds);
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
                .filter(nodeId -> explicitNodeIds.contains(nodeId)
                        || isClassNode(detail, nodeId)
                        || !isAccessorLikeMethod(detail, nodeId))
                .limit(MAX_CONTEXT_SYMBOLS)
                .toList();
    }

    private void addCodingNeighborhood(ProjectDetail detail, String selectedNodeId, Set<String> nodeIds) {
        Optional<GraphNode> selected = detail.graphNodes().stream()
                .filter(node -> node.id().equals(selectedNodeId))
                .findFirst();
        if (selected.isPresent() && "class".equals(selected.get().type())) {
            detail.classes().stream()
                    .filter(classInfo -> classInfo.id().equals(selectedNodeId))
                    .findFirst()
                    .ifPresent(classInfo -> classInfo.methods().stream()
                            .filter(method -> !isAccessorLikeMethod(method))
                            .limit(MAX_CONTEXT_CLASS_METHODS)
                            .map(MethodInfo::id)
                            .forEach(nodeIds::add));
            return;
        }
        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> edge.source().equals(selectedNodeId) || edge.target().equals(selectedNodeId))
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .limit(12)
                .forEach(edge -> {
                    nodeIds.add(edge.source());
                    nodeIds.add(edge.target());
                });
    }

    private boolean isClassNode(ProjectDetail detail, String nodeId) {
        return detail.graphNodes().stream()
                .anyMatch(node -> node.id().equals(nodeId) && "class".equals(node.type()));
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

    private List<String> relevantCodingSnippets(
            ProjectDetail detail,
            String selectedNodeId,
            FlowTrace trace,
            List<String> contextNodeIds
    ) {
        Set<String> explicitNodeIds = new LinkedHashSet<>();
        if (trace != null && trace.nodeIds() != null) {
            explicitNodeIds.addAll(trace.nodeIds());
        }
        if (selectedNodeId != null && !selectedNodeId.isBlank()) {
            explicitNodeIds.add(selectedNodeId);
        }

        Set<String> nodeIds = new LinkedHashSet<>(explicitNodeIds);
        for (String nodeId : contextNodeIds) {
            if (nodeIds.size() >= MAX_CONTEXT_SNIPPETS) {
                break;
            }
            if (explicitNodeIds.contains(nodeId) || isClassNode(detail, nodeId) || !isAccessorLikeMethod(detail, nodeId)) {
                nodeIds.add(nodeId);
            }
        }

        List<String> snippets = new ArrayList<>();
        for (String nodeId : nodeIds) {
            findCodingSnippet(detail, nodeId).ifPresent(snippets::add);
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) {
                break;
            }
        }
        return snippets;
    }

    private List<GraphEdge> codingContextDirectEdges(ProjectDetail detail, Set<String> contextNodeIdSet) {
        return detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> contextNodeIdSet.contains(edge.source()))
                .sorted(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target))
                .limit(MAX_CONTEXT_DIRECT_EDGES)
                .toList();
    }

    private List<String> compressedAccessorFacts(
            ProjectDetail detail,
            AiCodingContextRequest request,
            Set<String> contextNodeIdSet
    ) {
        Set<String> accessorIds = new LinkedHashSet<>();
        String selectedNodeId = request.selectedNodeId();
        if (selectedNodeId != null && !selectedNodeId.isBlank() && isClassNode(detail, selectedNodeId)) {
            detail.classes().stream()
                    .filter(classInfo -> classInfo.id().equals(selectedNodeId))
                    .findFirst()
                    .ifPresent(classInfo -> classInfo.methods().stream()
                            .filter(this::isAccessorLikeMethod)
                            .map(MethodInfo::id)
                            .filter(methodId -> !contextNodeIdSet.contains(methodId))
                            .forEach(accessorIds::add));
        }

        detail.graphEdges().stream()
                .filter(edge -> "CALLS".equals(edge.type()))
                .filter(edge -> contextNodeIdSet.contains(edge.source()))
                .map(GraphEdge::target)
                .filter(target -> !contextNodeIdSet.contains(target))
                .filter(target -> isAccessorLikeMethod(detail, target))
                .forEach(accessorIds::add);

        return accessorIds.stream()
                .map(accessorId -> accessorFact(detail, accessorId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .limit(12)
                .toList();
    }

    private Optional<String> accessorFact(ProjectDetail detail, String methodId) {
        for (ClassInfo classInfo : detail.classes()) {
            Optional<MethodInfo> method = classInfo.methods().stream()
                    .filter(item -> item.id().equals(methodId))
                    .findFirst();
            if (method.isPresent()) {
                MethodInfo item = method.get();
                return Optional.of(classInfo.name() + "." + item.signature()
                        + " -> " + item.returnType()
                        + " @ " + Path.of(classInfo.filePath()).getFileName()
                        + ":" + item.beginLine());
            }
        }
        return Optional.empty();
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

    private AiFlowDraftResponse parseFlowRecommendationResponse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI did not return recommended flows.");
        }
        String json = extractJsonValue(content.trim());
        try {
            return flowDraftResponseFrom(objectMapper.readTree(json));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AI returned recommended flows that could not be parsed.", ex);
        }
    }

    private AiFlowDraftResponse flowDraftResponseFrom(JsonNode root) {
        JsonNode flowsNode = flowArrayNode(root);
        if (flowsNode == null || !flowsNode.isArray()) {
            return new AiFlowDraftResponse(List.of());
        }
        List<AiFlowDraft> flows = new ArrayList<>();
        for (JsonNode flowNode : flowsNode) {
            if (flowNode == null || flowNode.isNull()) {
                continue;
            }
            List<String> nodeIds = stringValues(firstPresent(flowNode, "nodeIds", "nodes", "path", "sequence"));
            List<AiFlowStepDraft> steps = stepDrafts(flowNode.get("steps"), nodeIds);
            if (nodeIds.isEmpty() && !steps.isEmpty()) {
                nodeIds = steps.stream()
                        .map(AiFlowStepDraft::nodeId)
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .toList();
            }
            flows.add(new AiFlowDraft(
                    textValue(firstPresent(flowNode, "title", "name", "flowName")),
                    textValue(firstPresent(flowNode, "summary", "description", "purpose")),
                    nodeIds,
                    stringValues(firstPresent(flowNode, "tags", "labels")),
                    steps));
        }
        return new AiFlowDraftResponse(flows);
    }

    private JsonNode flowArrayNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        JsonNode flows = firstPresent(root, "flows", "recommendedFlows", "flowRecommendations", "items", "data");
        if (flows != null && flows.isArray()) {
            return flows;
        }
        JsonNode singleFlow = firstPresent(root, "flow", "recommendedFlow");
        if (singleFlow != null && singleFlow.isObject()) {
            return objectMapper.createArrayNode().add(singleFlow);
        }
        return null;
    }

    private JsonNode firstPresent(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private List<String> stringValues(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = nodeReferenceValue(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        } else {
            String value = nodeReferenceValue(node);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.stream().distinct().toList();
    }

    private String nodeReferenceValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText().trim();
        }
        if (node.isObject()) {
            return textValue(firstPresent(node, "nodeId", "id", "targetNodeId", "target", "node"));
        }
        return "";
    }

    private List<AiFlowStepDraft> stepDrafts(JsonNode stepsNode, List<String> nodeIds) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return List.of();
        }
        List<AiFlowStepDraft> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode stepNode : stepsNode) {
            String nodeId = "";
            String description = "";
            if (stepNode != null && stepNode.isObject()) {
                nodeId = textValue(firstPresent(stepNode, "nodeId", "id", "targetNodeId", "target", "node"));
                description = textValue(firstPresent(stepNode, "description", "summary", "reason", "title", "text"));
            } else {
                description = textValue(stepNode);
            }
            if (nodeId.isBlank() && index < nodeIds.size()) {
                nodeId = nodeIds.get(index);
            }
            if (!nodeId.isBlank() || !description.isBlank()) {
                steps.add(new AiFlowStepDraft(nodeId, description));
            }
            index++;
        }
        return steps;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("").trim();
        }
        return "";
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

    private String extractJsonValue(String value) {
        String next = value;
        if (next.startsWith("```")) {
            int firstLineBreak = next.indexOf('\n');
            int lastFence = next.lastIndexOf("```");
            if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
                next = next.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        int objectStart = next.indexOf('{');
        int arrayStart = next.indexOf('[');
        int start;
        char close;
        if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            start = arrayStart;
            close = ']';
        } else {
            start = objectStart;
            close = '}';
        }
        int end = close == ']' ? next.lastIndexOf(']') : next.lastIndexOf('}');
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

    private AiFlowRecommendationResponse normalizeFlowRecommendationResponse(
            ProjectDetail detail,
            AiFlowRecommendationRequest request,
            AiFlowDraftResponse raw,
            String model
    ) {
        Map<String, GraphNode> graphNodes = new HashMap<>();
        for (GraphNode node : detail.graphNodes()) {
            graphNodes.put(node.id(), node);
        }
        Map<String, AiFlowStepDraft> stepDrafts = new HashMap<>();
        List<FlowInfo> flows = new ArrayList<>();
        Set<String> flowKeys = new LinkedHashSet<>();
        List<AiFlowDraft> rawFlows = raw.flows() == null ? List.of() : raw.flows();
        int index = 1;
        for (AiFlowDraft rawFlow : rawFlows) {
            if (rawFlow == null || rawFlow.nodeIds() == null) {
                continue;
            }
            List<String> nodeIds = rawFlow.nodeIds().stream()
                    .filter(nodeId -> nodeId != null && graphNodes.containsKey(nodeId))
                    .distinct()
                    .limit(9)
                    .toList();
            if (nodeIds.size() < 3) {
                continue;
            }
            String key = String.join(">", nodeIds);
            if (!flowKeys.add(key)) {
                continue;
            }
            stepDrafts.clear();
            if (rawFlow.steps() != null) {
                for (AiFlowStepDraft step : rawFlow.steps()) {
                    if (step != null && step.nodeId() != null) {
                        stepDrafts.put(step.nodeId(), step);
                    }
                }
            }
            String title = defaultIfBlank(rawFlow.title(), fallbackFlowTitle(detail, nodeIds));
            List<FlowStep> steps = new ArrayList<>();
            for (String nodeId : nodeIds) {
                GraphNode node = graphNodes.get(nodeId);
                MethodInfo method = findMethod(detail, nodeId).orElse(null);
                AiFlowStepDraft stepDraft = stepDrafts.get(nodeId);
                String description = defaultIfBlank(
                        stepDraft == null ? "" : stepDraft.description(),
                        method == null ? graphNodeRole(detail, node) : methodFactSentence(method));
                steps.add(new FlowStep(
                        nodeId,
                        readableNodeLabel(detail, node),
                        description,
                        node.filePath(),
                        node.line() == null ? 1 : node.line(),
                        method == null ? List.of() : method.readsFields(),
                        method == null ? List.of() : method.writesFields()));
            }
            flows.add(new FlowInfo(
                    "ai-flow-" + index + "-" + slug(title),
                    title,
                    defaultIfBlank(rawFlow.summary(), "AI 基于当前选中代码生成的阅读 Flow。"),
                    nodeIds.get(0),
                    nodeIds,
                    "ai",
                    0.82,
                    normalizeTags(rawFlow.tags()),
                    steps));
            index++;
            if (flows.size() >= 5) {
                break;
            }
        }
        return new AiFlowRecommendationResponse(flows, model);
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

    private String fallbackFlowTitle(ProjectDetail detail, List<String> nodeIds) {
        GraphNode first = detail.graphNodes().stream()
                .filter(node -> node.id().equals(nodeIds.get(0)))
                .findFirst()
                .orElse(null);
        GraphNode last = detail.graphNodes().stream()
                .filter(node -> node.id().equals(nodeIds.get(nodeIds.size() - 1)))
                .findFirst()
                .orElse(null);
        if (first == null || last == null) {
            return "AI 推荐 Flow";
        }
        return readableNodeLabel(detail, first) + " -> " + readableNodeLabel(detail, last);
    }

    private String methodFactSentence(MethodInfo method) {
        List<String> facts = new ArrayList<>();
        if (!method.writesFields().isEmpty()) {
            facts.add("写入 " + String.join("、", method.writesFields().stream().limit(4).toList()));
        }
        if (!method.readsFields().isEmpty()) {
            facts.add("读取 " + String.join("、", method.readsFields().stream().limit(4).toList()));
        }
        if (!method.calls().isEmpty()) {
            facts.add("调用 " + String.join("、", method.calls().stream().limit(4).toList()));
        }
        if (facts.isEmpty()) {
            return "执行 " + method.signature() + "。";
        }
        return String.join("；", facts) + "。";
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of("ai");
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .limit(4)
                .toList();
    }

    private String slug(String value) {
        String ascii = value == null ? "" : value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return ascii.isBlank() ? "recommended" : ascii;
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

    private boolean isAccessorLikeMethod(ProjectDetail detail, String nodeId) {
        return findMethod(detail, nodeId)
                .map(this::isAccessorLikeMethod)
                .orElse(false);
    }

    private boolean isAccessorLikeMethod(MethodInfo method) {
        String name = method.name().toLowerCase();
        Set<String> commonAccessors = Set.of(
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
                "logsummary"
        );
        return method.calls().isEmpty()
                && !"void".equals(method.returnType())
                && (commonAccessors.contains(name) || name.startsWith("get") || name.startsWith("is"));
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Optional<String> findCodingSnippet(ProjectDetail detail, String nodeId) {
        for (ClassInfo classInfo : detail.classes()) {
            if (classInfo.id().equals(nodeId)) {
                return readClassHeaderSnippet(classInfo);
            }
            for (MethodInfo method : classInfo.methods()) {
                if (method.id().equals(nodeId)) {
                    return readSnippet(classInfo.name() + "." + method.name(), classInfo.filePath(), method.beginLine(), method.endLine());
                }
            }
        }
        return Optional.empty();
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

    private Optional<String> readClassHeaderSnippet(ClassInfo classInfo) {
        int firstMethodLine = classInfo.methods().stream()
                .map(MethodInfo::beginLine)
                .min(Integer::compareTo)
                .orElse(classInfo.endLine() + 1);
        int endLine = Math.max(classInfo.beginLine(), Math.min(classInfo.endLine(), firstMethodLine - 1));
        return readSnippet(classInfo.name() + " class header", classInfo.filePath(), classInfo.beginLine(), endLine);
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

    private record AiFlowDraftResponse(List<AiFlowDraft> flows) {
    }

    private record AiFlowDraft(
            String title,
            String summary,
            List<String> nodeIds,
            List<String> tags,
            List<AiFlowStepDraft> steps
    ) {
    }

    private record AiFlowStepDraft(String nodeId, String description) {
    }
}

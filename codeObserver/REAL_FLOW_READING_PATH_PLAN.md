# Real Flow Reading Path Execution Plan

## Goal

把 Code Observer 的阅读路径从“按类排序的推荐列表”升级成“按真实行为组织的 Flow”。

现状更像这样：

```text
1. Main
2. ClientResponse
3. LogEntry
4. RaftNode
5. RaftRole
6. RaftClusterDemo
```

目标是变成这样：

```text
客户端写入与日志提交
RaftClusterDemo.run
-> InMemoryRaftCluster.propose
-> RaftNode.propose
-> RaftNode.replicateToFollowers
-> RaftNode.replicateToPeer
-> RaftNode.sendAppendEntries
-> InMemoryRaftCluster.appendEntries
-> RaftNode.onAppendEntries
-> RaftNode.applyCommittedEntries
```

这样用户不是先看到一堆类，而是先看到“一个具体行为发生时，代码实际怎么走、状态怎么变”。这会同时提升源码阅读效率和 AI Coding Context 的质量。

## Product Outcome

用户进入一个项目后，首页应该展示“推荐 Flow”，例如：

- 选主流程
- 客户端写入与日志提交
- Leader 故障切换
- 节点恢复与日志追赶

点进一个 Flow 后，用户可以看到：

- 这个 Flow 解决什么问题。
- 入口方法在哪里。
- 真实调用步骤。
- 每一步对应的源码位置。
- 每一步大概读写了什么状态。
- 哪些方法是关键步骤，哪些可以先跳过。
- 一键生成围绕该 Flow 的 AI Coding Context。

## Current Baseline

- 后端 `JavaProjectAnalyzer` 已经能解析类、方法、字段、调用名和对象创建。
- 后端 `ProjectDetail` 当前返回 `readingPath`，但没有 `flows`。
- `readingPath` 当前在 `JavaProjectAnalyzer.buildReadingPath` 中生成，主要按类名和 `main/run` 排序。
- 前端 `buildBusinessTraces(detail)` 会基于 `CALLS` 边从 `main/run` 做 DFS，生成临时调用链。
- AI Context 生成已经支持 `trace`，但 trace 由前端临时计算，不是后端稳定模型。
- 当前调用解析主要按方法名匹配，重名、重载、跨类 receiver type 等场景还不够精确。

## Example: Raft Flow

### Flow 1: 选主流程

```text
RaftClusterDemo.run
-> InMemoryRaftCluster.runUntilLeaderElected
-> InMemoryRaftCluster.tickAll
-> RaftNode.tick
-> RaftNode.startElection
-> InMemoryRaftCluster.requestVote
-> RaftNode.onRequestVote
-> RaftNode.becomeLeader
```

用户理解点：

- follower 超时后进入 candidate。
- candidate 增加 term，并给自己投票。
- candidate 向 peers 发起 `RequestVoteRequest`。
- follower 在 `onRequestVote` 中判断 term、日志新旧和投票状态。
- 票数过半后调用 `becomeLeader`。
- leader 初始化 `nextIndex/matchIndex`，追加 noop 日志，并复制给 follower。

### Flow 2: 客户端写入与日志提交

```text
RaftClusterDemo.run
-> InMemoryRaftCluster.propose
-> RaftNode.propose
-> RaftNode.replicateToFollowers
-> RaftNode.replicateToPeer
-> RaftNode.sendAppendEntries
-> InMemoryRaftCluster.appendEntries
-> RaftNode.onAppendEntries
-> RaftNode.advanceCommitIndex
-> RaftNode.applyCommittedEntries
```

用户理解点：

- demo 通过 cluster 提交命令。
- cluster 找到当前 leader。
- leader 把 command append 到本地 log。
- leader 给每个 peer 发送 AppendEntries。
- follower 校验 prev log 后追加日志。
- leader 根据 `matchIndex` 推进 `commitIndex`。
- 已提交日志被应用到状态机。

### Flow 3: Leader 故障切换

```text
RaftClusterDemo.run
-> InMemoryRaftCluster.stop
-> InMemoryRaftCluster.runUntilLeaderElected
-> InMemoryRaftCluster.tickAll
-> RaftNode.tick
-> RaftNode.startElection
-> RaftNode.becomeLeader
```

用户理解点：

- 旧 leader 被加入 stoppedNodes。
- 剩余节点继续 tick。
- follower 超时后重新发起选举。
- 新 leader 产生后继续接受写入。

### Flow 4: 节点恢复与日志追赶

```text
RaftClusterDemo.run
-> InMemoryRaftCluster.start
-> RaftNode.restartElectionTimer
-> InMemoryRaftCluster.runTicks
-> InMemoryRaftCluster.tickAll
-> RaftNode.tick
-> RaftNode.replicateToFollowers
-> RaftNode.sendAppendEntries
-> RaftNode.onAppendEntries
```

用户理解点：

- 旧 leader 恢复后重新加入 running nodes。
- 当前 leader 通过 heartbeat / AppendEntries 同步日志。
- 恢复节点在 `onAppendEntries` 中补齐日志并应用提交记录。

## Phase 1: Add Backend Flow Model

### Model Changes

在 `WorkspaceModels` 中新增 Flow 模型。

Proposed records:

```java
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
```

Extend `ProjectDetail`:

```java
List<FlowInfo> flows
```

Keep `readingPath` for compatibility during migration.

### Source Kind

Use these values first:

- `main`
- `run`
- `controller`
- `test`
- `heuristic`
- `manual`

MVP only needs `main`, `run`, and `heuristic`.

## Phase 2: Build Flow Analyzer

新增 `FlowAnalyzer`，或者先在 `JavaProjectAnalyzer` 中新增独立方法。建议长期拆成单独类，避免 `JavaProjectAnalyzer` 继续变大。

### Entry Detection

MVP entry methods:

- `main`
- `run`

Next entries:

- Spring Controller handler
- test methods
- scheduler methods
- message consumer methods
- CLI command handler

### Path Collection

从入口方法沿 `CALLS` 边搜索。

MVP rules:

- Max depth: 8
- Max branches per node: 3
- Skip obvious accessor-like methods:
  - `get*`
  - `is*`
  - `id`
  - `role`
  - `currentTerm`
  - `leaderId`
  - `lastLogIndex`
  - `logSummary`
  - `stateSnapshot`
- Skip formatting/logging-only methods where possible:
  - `print`
  - `println`
  - `printf`
  - `toString`
- Avoid cycles by checking whether node already appears in current path.

### Flow Scoring

Each collected path gets a score.

Positive signals:

- Contains behavior words:
  - `propose`
  - `append`
  - `vote`
  - `elect`
  - `leader`
  - `commit`
  - `apply`
  - `replicate`
  - `fail`
  - `recover`
  - `transaction`
  - `lock`
  - `index`
- Contains object creation edges.
- Contains cross-class calls.
- Contains methods with `void` return and meaningful downstream calls.
- Path reaches a method that mutates state or applies a command.
- Path length is between 4 and 10.

Negative signals:

- Accessor-heavy path.
- Path ends at simple getter.
- Path mostly stays inside records/data objects.
- Path is shorter than 3 nodes.
- Path contains only wrapper methods and no domain verbs.

### Merge Similar Paths

Deduplicate paths by:

- Same entry node and same terminal node.
- Same ordered node id list.
- Same title.

When duplicates exist, keep the highest-scoring path.

### Flow Title Generation

Use rule-based names before AI.

Rules:

```text
contains propose + appendEntries + applyCommittedEntries
=> 客户端写入与日志提交

contains tick + startElection + requestVote + becomeLeader
=> 选主流程

contains stop + runUntilLeaderElected + becomeLeader
=> Leader 故障切换

contains start + restartElectionTimer + appendEntries
=> 节点恢复与日志追赶
```

Fallback:

```text
{EntryOwner}.{entryMethod} -> {TerminalOwner}.{terminalMethod}
```

## Phase 3: Frontend Uses Backend Flows

### Type Changes

Add to `types.ts`:

```ts
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
```

Update `ProjectDetail`:

```ts
flows: FlowInfo[];
```

### Migration Strategy

Frontend currently computes traces in `buildBusinessTraces(detail)`.

Change behavior:

```text
if detail.flows exists and is not empty:
  render backend flows
else:
  fallback to buildBusinessTraces(detail)
```

This keeps the UI working while backend Flow generation evolves.

### UI Changes

Rename the current “调用链” panel to “推荐 Flow”.

Flow card content:

- title
- summary
- sourceKind badge
- confidence badge
- step count
- tags

Step list content:

- step index
- method label
- description
- file line
- state reads/writes, when available

Graph behavior:

- Selecting a Flow renders only Flow nodes and direct Flow edges.
- Selecting a step opens the source drawer at the method line.
- The AI panel should use selected Flow as primary context.

## Phase 4: AI Context Uses Flow

### Request Model

Extend `AiSummaryRequest`, `AiCallGraphRequest`, and `AiCodingContextRequest` with:

```java
String flowId
```

Keep existing `trace` for compatibility.

Resolution order:

```text
1. flowId
2. trace
3. selectedNodeId
4. project fallback
```

### Prompt Changes

When `flowId` exists:

- Use `flow.title`.
- Use `flow.summary`.
- Use `flow.steps`.
- Use `flow.nodeIds`.
- Prefer source snippets from Flow nodes.

Recommended output structure:

```markdown
# Coding Context
## User Task
## Selected Flow
## Flow Steps
## Project Facts
## Symbol Facts
## Direct Call Facts
## Source Snippet Facts
```

The important change is that Context becomes behavior-centered, not class-centered.

## Phase 5: Add Basic State Facts

MVP can ship with empty `stateReads` and `stateWrites`, but the next useful upgrade is lightweight state extraction.

### State Write Signals

Detect assignments and mutations inside method bodies:

- `role = ...`
- `currentTerm++`
- `votedFor = ...`
- `leaderId = ...`
- `commitIndex = ...`
- `log.add(...)`
- `nextIndex.put(...)`
- `matchIndex.put(...)`
- `stateMachine.apply(...)`

### State Read Signals

Detect simple reads:

- `if (role == ...)`
- `if (request.term() < currentTerm)`
- `lastLogIndex()`
- `matchIndex.getOrDefault(...)`
- `commitIndex`

First implementation can store string facts, not full AST references.

Example:

```text
stateWrites:
- role = CANDIDATE
- currentTerm++
- votedFor = id

stateReads:
- electionElapsed
- electionTimeoutTicks
```

## Phase 6: Improve Call Resolution

After the Flow UX works, improve analysis precision.

Options:

- JavaParser Symbol Solver
- Eclipse JDT

Target improvements:

- Resolve receiver type for method calls.
- Resolve overloads.
- Resolve constructors.
- Resolve interface implementation calls.
- Resolve call-site source spans.
- Connect `transport.appendEntries(...)` to `InMemoryRaftCluster.appendEntries(...)` when concrete project wiring makes it knowable.

This phase should be done after MVP because the product value can be validated with current graph edges first.

## Test Plan

### Backend Golden Tests

Add fixtures using the existing `Raft` project.

Minimum expected flows:

```text
选主流程
客户端写入与日志提交
Leader 故障切换
节点恢复与日志追赶
```

Assertions:

- `ProjectDetail.flows` is not empty.
- Flow titles are stable.
- Each Flow has an entry node.
- Each Flow has at least 3 steps.
- Flow node ids exist in `graphNodes`.
- Flow step file paths and lines are valid.
- Accessor-like tail nodes are trimmed.

### Frontend Tests

Minimum checks:

- Prefer `detail.flows` over local `buildBusinessTraces`.
- Fallback works when `flows` is absent or empty.
- Selecting a Flow updates graph and source links.
- Context request sends `flowId`.

### Manual QA

Run these projects:

- `Raft`
- `kafka`
- `mysql`
- `redis`
- `codeObserver/server`

For each project verify:

- Recommended Flow cards are useful.
- Flow titles are not misleading.
- Clicking each step opens the expected source line.
- Context output is centered on the selected Flow.

## Acceptance Criteria

MVP is done when:

- Backend returns `flows` in `ProjectDetail`.
- Frontend displays backend-generated Flow cards.
- Selecting a Flow renders a Flow graph and step list.
- Selecting a step opens the source drawer at the correct line.
- AI Context generation uses the selected Flow.
- Raft produces at least:
  - 选主流程
  - 客户端写入与日志提交
  - Leader 故障切换
- Existing `readingPath` still works as fallback.
- `npm run build` passes.
- `mvn test` passes.

## Rollout Plan

### Milestone 1: Backend Flow MVP

- Add `FlowInfo` and `FlowStep`.
- Add `flows` to `ProjectDetail`.
- Generate flows from current `CALLS` graph.
- Add rule-based titles.
- Add golden tests for Raft.

### Milestone 2: Frontend Flow UI

- Add Flow types.
- Prefer backend `flows`.
- Rename panel to “推荐 Flow”.
- Render Flow steps and Flow graph.
- Keep frontend trace fallback.

### Milestone 3: Context Integration

- Add `flowId` to AI requests.
- Resolve flow server-side.
- Include Flow facts in prompt.
- Update copy/context UI labels where needed.

### Milestone 4: State Facts

- Extract simple field reads/writes.
- Show state facts in Flow steps.
- Include state facts in Context.

### Milestone 5: Precision Upgrade

- Introduce Symbol Solver or JDT.
- Add call-site spans.
- Improve cross-class and interface call resolution.

## Risks

### Risk: Flow title is wrong

Mitigation:

- Prefer conservative rule names.
- Use fallback title when confidence is low.
- Show confidence in UI.

### Risk: Current call graph misses important edges

Mitigation:

- Ship MVP with current graph.
- Keep fallback to existing trace generation.
- Improve resolver in later phase.

### Risk: Too many generated flows

Mitigation:

- Score and rank flows.
- Cap display to top 8 or 12.
- Group flows by source kind or tags.

### Risk: Flow becomes another noisy graph

Mitigation:

- Trim accessor-like methods.
- Show only behavior steps.
- Keep source panel as the main reading surface.

## Implementation Notes

Preferred sequence:

1. Add backend model and compile.
2. Generate simple backend flows using current `CALLS` edges.
3. Add Raft golden tests.
4. Update frontend types and UI fallback.
5. Wire `flowId` into AI requests.
6. Improve state facts and resolver precision.

The first useful version should stay small: make it possible to click a Flow, read the behavior path, and generate Flow-centered Context. Once that loop feels good, deeper static analysis will have a clear target.

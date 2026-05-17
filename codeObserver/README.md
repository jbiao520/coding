# Code Observer

Code Observer 是一个本地源码观察器，用来把 AI 生成的 Java 教学代码拆成更容易阅读的图形化视图。它不绑定 Kafka 或 MySQL，可以扫描任意包含 Java 文件、`pom.xml` 或 `src/main/java` 的目录。

## 能力

- 扫描任意工作区，自动发现 Java/Maven 项目。
- 使用 JavaParser 解析类、record、enum、接口、字段和方法。
- 生成类和方法的调用图、创建关系和包含关系；前端默认只展示当前选中的类或方法的聚焦子图。
- 在调用图里一键生成 AI 精简图，让模型保留关键业务/状态转换步骤，并给每个节点和跳转补上面向学习者的解释。
- 自动从 `main` / `run` 入口推导调用链，按链路展示端到端步骤，帮助第一次进入项目的用户先理解整体流程。
- 选中调用链后生成可复制的 AI 编程 Context，用便宜模型先压缩链路、源码位置、符号元数据和直接调用事实，再交给更强模型写代码。
- 根据类名、方法名、README 和源码提取概念标签。
- 提供推荐阅读路径。
- 点击类、方法或图节点后，在源码面板定位到对应行。

## 运行

启动后端：

```bash
cd codeObserver/server
mvn spring-boot:run
```

启动前端：

```bash
cd codeObserver/web
npm install
npm run dev
```

打开：

```text
http://localhost:5188
```

后端默认端口是 `8088`。前端默认会请求 `http://localhost:8088`，如果需要改 API 地址，可以设置：

```bash
VITE_API_BASE=http://localhost:8088 npm run dev
```

## API

扫描工作区：

```bash
curl 'http://localhost:8088/api/workspace?root=/Users/jianguo/IdeaProjects/coding'
```

加载项目详情：

```bash
curl 'http://localhost:8088/api/projects/{projectId}?root=/Users/jianguo/IdeaProjects/coding'
```

读取源码：

```bash
curl 'http://localhost:8088/api/source?path=/absolute/path/File.java'
```

生成 AI 精简调用图：

```bash
curl -X POST 'http://localhost:8088/api/ai/call-graph' \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"Raft","root":"/Users/jianguo/IdeaProjects/coding","selectedNodeId":"Raft::com.example.raft.Main#main:8"}'
```

生成 AI 编程 Context：

```bash
curl -X POST 'http://localhost:8088/api/ai/context' \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"Raft","root":"/Users/jianguo/IdeaProjects/coding","selectedNodeId":"Raft::com.example.raft.Main#main:8","task":"准备修改 leader 选举流程"}'
```

流式生成 AI 编程 Context：

```bash
curl -N -X POST 'http://localhost:8088/api/ai/context/stream' \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"Raft","root":"/Users/jianguo/IdeaProjects/coding","selectedNodeId":"Raft::com.example.raft.Main#main:8","task":"准备修改 leader 选举流程"}'
```

默认使用 `LEARNING_FLOW_AI_CONTEXT_MODEL` 指定的便宜模型生成 Context；未配置时使用 `deepseek-v4-flash`。

## 设计取舍

默认调用图仍来自确定性的源码结构分析，保证图、源码定位和阅读路径可以稳定复现。AI 精简调用图是在这个基础上的解释层：它只从当前候选节点里挑重点，不修改源码分析结果。

# Source IDE Experience Execution Plan

## Goal

Bring the source reading panel closer to an IDE experience:

- Show `who calls me` and `what I call`.
- Highlight exact call sites instead of matching method names as plain text.
- Pin multiple source snippets into a coding context.
- Show field reads/writes, object creation, and return-value facts.
- Open a source file at a specific line in the local IDE.

## Current Baseline

- Backend parses Java with JavaParser and builds classes, methods, fields, call edges, and creation edges.
- `MethodInfo.calls` and `MethodInfo.creates` currently store symbol names only.
- `GraphEdge` stores `source`, `target`, `type`, and `label`, but no source location.
- Frontend source panel locates call sites by scanning source lines for method names.
- Focused method graph already computes incoming and outgoing `CALLS` edges.

## Phase 1: Add Structured Source References

### Backend Model

Add source-location models to `WorkspaceModels`.

Proposed records:

```java
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
```

Reference kinds:

- `CALL`
- `FIELD_READ`
- `FIELD_WRITE`
- `CREATE`
- `RETURN`

Add to `ProjectDetail`:

```java
List<CodeReference> references
```

### Analyzer Changes

Update `JavaProjectAnalyzer` to extract references from each method body:

- `MethodCallExpr` -> `CALL`
- `ObjectCreationExpr` -> `CREATE`
- `NameExpr`, `FieldAccessExpr`, `AssignExpr`, `UnaryExpr` -> field reads/writes
- `ReturnStmt` -> `RETURN`

For call references:

- Keep existing name-based resolution as phase-1 resolver.
- Store the actual AST span from `MethodCallExpr.getName()`.
- Preserve all call sites, even if graph edges are deduplicated.

For object creation:

- Store type name and span from the created type.
- Resolve to class node when possible.

For field references:

- Match against fields declared on the owning class.
- Start with direct field names, `this.field`, and simple assignments.
- Mark unresolved field-like expressions as omitted rather than guessed.

## Phase 2: Exact Source Highlighting

### Frontend Types

Add `SourceSpan` and `CodeReference` to `types.ts`.

Update `ProjectDetail` with:

```ts
references: CodeReference[];
```

### Source Panel Rendering

Replace line-level method-name matching with span-based rendering.

Implementation outline:

- Build references for the current source file.
- Group references by `beginLine`.
- Split each source line into text segments and reference segments.
- Render reference segments as buttons/tooltips.
- For multi-line spans, highlight the first line token in phase 1 and add full multi-line support later if needed.

Reference interactions:

- `CALL`: jump to target method when resolved.
- `CREATE`: jump to target class when resolved.
- `FIELD_READ` / `FIELD_WRITE`: jump to field declaration when available.
- `RETURN`: show return expression tooltip.

Remove or reduce use of `lineCallsMethod`.

## Phase 3: Who Calls Me / What I Call

Add an IDE-style relationship panel near the source toolbar or as a compact section above the source.

For selected method:

- `Who calls me`: incoming `CALL` references grouped by caller method.
- `What I call`: outgoing `CALL` references grouped by target method.
- `Creates`: outgoing `CREATE` references.
- `Reads/Writes`: field references in current method.
- `Returns`: return statements in current method.

Each item should show:

- Symbol label.
- Owner method/class.
- `file:line`.
- Small kind badge.

Click behavior:

- Click relationship item -> open source drawer and scroll to exact reference span.
- Click target symbol -> jump to declaration.

## Phase 4: Pin Source Snippets Into Context

### Frontend State

Add pinned snippets:

```ts
type PinnedSourceSnippet = {
  id: string;
  label: string;
  filePath: string;
  beginLine: number;
  endLine: number;
  nodeId?: string;
  source: string;
};
```

Pin actions:

- Pin current selected method.
- Pin current class.
- Pin exact reference line.
- Pin selected trace nodes.
- Remove one pin.
- Clear all pins.

### Backend Request

Extend `AiCodingContextRequest` with:

```java
List<PinnedSourceSnippet> pinnedSnippets
```

The AI context prompt should include pinned snippets as explicit user-selected context, separate from automatically selected snippets.

## Phase 5: Open In IDE

### Backend Endpoint

Add endpoint:

```text
POST /api/source/open-in-ide
```

Request:

```json
{
  "path": "/absolute/path/File.java",
  "line": 123
}
```

Safety rules:

- Only open files under the last scanned workspace root.
- Only open regular files.
- Reject missing paths or paths outside root.

Command strategy:

- Prefer configurable env var: `OBSERVER_IDE_COMMAND`.
- Default candidates on macOS:
  - `idea --line {line} {file}`
  - `open -a "IntelliJ IDEA" {file}`

Return a small status object so the UI can show success/failure.

### Frontend Button

Add an icon button in the source toolbar:

- Tooltip: `在 IDE 打开`
- Disabled when no source is loaded.
- Calls backend endpoint with current `source.path` and highlighted line.

## Phase 6: Optional Symbol Solver Upgrade

Add `javaparser-symbol-solver-core` after phases 1-5 are stable.

Use it to improve:

- Overloaded method resolution.
- Cross-class calls.
- Static imports.
- Interface and parent-class dispatch.
- Field resolution beyond simple owner-class fields.

This phase should not block the first IDE-like experience.

## Suggested Implementation Order

1. Add backend `SourceSpan` / `CodeReference` models and JSON output.
2. Extract `CALL` and `CREATE` references with AST spans.
3. Update frontend types and render exact call/create highlights.
4. Add relationship panel for `who calls me` / `what I call`.
5. Extract `RETURN`, `FIELD_READ`, and `FIELD_WRITE`.
6. Add pin snippets and pass them into AI Context.
7. Add open-in-IDE endpoint and toolbar button.
8. Consider Symbol Solver upgrade.

## Acceptance Criteria

- Clicking a call-site token jumps to the resolved method declaration.
- Repeated calls to the same method on different lines are highlighted independently.
- A line containing a method name in a comment or string is not highlighted as a call site.
- `Who calls me` lists real caller locations for the selected method.
- `What I call` lists real outgoing call locations for the selected method.
- Object creation sites show as `new Type` references and jump to the type when resolved.
- Field writes and reads are visually distinguishable.
- Return statements are visible in the relationship/facts area.
- Multiple pinned snippets can be added, removed, and included in generated AI Context.
- Open-in-IDE opens a scanned workspace file at or near the requested line.

## Risks And Constraints

- Without Symbol Solver, call resolution is still heuristic for overloads and polymorphism.
- JavaParser positions are available only when parsing succeeds.
- Field read/write detection should start conservative to avoid false confidence.
- Open-in-IDE needs local command differences handled gracefully.
- Large files may need memoization for span-based rendering.

## Test Plan

Backend:

- Add analyzer tests with overloaded methods, repeated calls, comments, strings, field writes, field reads, object creation, and returns.
- Verify generated `CodeReference` spans map to expected line/column positions.
- Verify open-in-IDE rejects paths outside workspace root.

Frontend:

- Typecheck with `npm run build`.
- Verify source rendering with multiple references on one line.
- Verify relationship panel click navigation.
- Verify pin add/remove/clear behavior.

Manual:

- Run server and web app.
- Scan the sample workspace.
- Open a project with several Java files.
- Select a method and inspect incoming/outgoing calls.
- Click highlighted call sites and confirm navigation accuracy.
- Generate AI Context with pinned snippets.

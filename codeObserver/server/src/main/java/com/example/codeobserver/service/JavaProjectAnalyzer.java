package com.example.codeobserver.service;

import static com.example.codeobserver.model.WorkspaceModels.CallSiteInfo;
import static com.example.codeobserver.model.WorkspaceModels.ClassInfo;
import static com.example.codeobserver.model.WorkspaceModels.CodeReference;
import static com.example.codeobserver.model.WorkspaceModels.FieldInfo;
import static com.example.codeobserver.model.WorkspaceModels.GraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.GraphNode;
import static com.example.codeobserver.model.WorkspaceModels.MethodInfo;
import static com.example.codeobserver.model.WorkspaceModels.ProjectDetail;
import static com.example.codeobserver.model.WorkspaceModels.ProjectSummary;
import static com.example.codeobserver.model.WorkspaceModels.ReadingStep;
import static com.example.codeobserver.model.WorkspaceModels.SourceFile;
import static com.example.codeobserver.model.WorkspaceModels.SourceSpan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithMembers;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.springframework.stereotype.Component;

@Component
public class JavaProjectAnalyzer {
    private final ConceptExtractor conceptExtractor;

    public JavaProjectAnalyzer(ConceptExtractor conceptExtractor) {
        this.conceptExtractor = conceptExtractor;
    }

    public ProjectDetail analyze(Path projectRoot, String projectId) {
        List<Path> javaFiles = collectJavaFiles(projectRoot);
        JavaParser parser = parserFor(projectRoot);
        List<SourceFile> sourceFiles = new ArrayList<>();
        List<ClassInfo> classes = new ArrayList<>();
        List<RawCodeReference> rawReferences = new ArrayList<>();
        Map<String, List<String>> methodNameIndex = new HashMap<>();

        for (Path javaFile : javaFiles) {
            parseFile(parser, projectRoot, projectId, javaFile).ifPresent(parsed -> {
                sourceFiles.add(parsed.sourceFile());
                classes.addAll(parsed.classes());
                rawReferences.addAll(parsed.references());
                for (ClassInfo classInfo : parsed.classes()) {
                    for (MethodInfo method : classInfo.methods()) {
                        methodNameIndex.computeIfAbsent(method.name(), ignored -> new ArrayList<>()).add(method.id());
                    }
                }
            });
        }

        List<CodeReference> references = resolveReferences(rawReferences, classes, methodNameIndex);

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        for (ClassInfo classInfo : classes) {
            nodes.add(new GraphNode(
                    classInfo.id(),
                    classInfo.name(),
                    "class",
                    classInfo.filePath(),
                    classInfo.beginLine(),
                    Map.of(
                            "kind", classInfo.kind(),
                            "qualifiedName", classInfo.qualifiedName(),
                            "superTypes", classInfo.superTypes(),
                            "concepts", classInfo.concepts()
                    )));
            for (MethodInfo method : classInfo.methods()) {
                nodes.add(new GraphNode(
                        method.id(),
                        method.name(),
                        "method",
                        classInfo.filePath(),
                        method.beginLine(),
                        Map.of(
                                "signature", method.signature(),
                                "returnType", method.returnType(),
                                "kind", method.kind(),
                                "parameterTypes", method.parameterTypes(),
                                "fieldReads", method.readsFields(),
                                "fieldWrites", method.writesFields(),
                                "branches", method.branchFacts(),
                                "exceptions", method.exceptionFacts(),
                                "concepts", method.concepts()
                        )));
                addEdge(edges, edgeIds, classInfo.id(), method.id(), "DECLARES", "declares", Map.of());
            }
            for (String nestedClassId : classInfo.nestedClasses()) {
                addEdge(edges, edgeIds, classInfo.id(), nestedClassId, "CONTAINS", "contains", Map.of());
            }
        }

        for (CodeReference reference : references) {
            if (reference.targetNodeId() == null || reference.targetNodeId().isBlank()) {
                continue;
            }
            if ("CALL".equals(reference.kind())) {
                addEdge(edges, edgeIds, reference.sourceNodeId(), reference.targetNodeId(), "CALLS",
                        reference.symbol() + "()", edgeData(reference));
                addImplementationEdges(edges, edgeIds, reference, classes);
            } else if ("CREATE".equals(reference.kind())) {
                addEdge(edges, edgeIds, reference.sourceNodeId(), reference.targetNodeId(), "CREATES",
                        "new " + reference.symbol(), edgeData(reference));
            }
        }

        ProjectSummary summary = summarize(projectRoot, projectId, javaFiles, classes);
        return new ProjectDetail(
                summary,
                sourceFiles,
                classes,
                nodes,
                edges,
                references,
                buildReadingPath(classes),
                List.of());
    }

    private Optional<ParsedFile> parseFile(JavaParser parser, Path projectRoot, String projectId, Path javaFile) {
        try {
            var parsed = parser.parse(javaFile);
            var cu = parsed.getResult()
                    .orElseThrow(() -> new IllegalArgumentException("Cannot parse " + javaFile));
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
            List<ClassInfo> classes = new ArrayList<>();
            List<RawCodeReference> references = new ArrayList<>();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                parseType(projectRoot, projectId, javaFile, packageName, "", type, classes, references);
            }
            SourceFile sourceFile = new SourceFile(
                    javaFile.toAbsolutePath().normalize().toString(),
                    projectRoot.relativize(javaFile).toString(),
                    packageName,
                    classes.stream().map(ClassInfo::qualifiedName).toList());
            return Optional.of(new ParsedFile(sourceFile, classes, references));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void parseType(
            Path projectRoot,
            String projectId,
            Path javaFile,
            String packageName,
            String ownerName,
            TypeDeclaration<?> type,
            List<ClassInfo> classes,
            List<RawCodeReference> references
    ) {
        String simpleName = type.getNameAsString();
        String localQualifiedName = joinOwner(ownerName, simpleName);
        String qualifiedName = packageName.isBlank() ? localQualifiedName : packageName + "." + localQualifiedName;
        String classId = projectId + "::" + qualifiedName;
        String filePath = javaFile.toAbsolutePath().normalize().toString();
        List<FieldInfo> fields = fieldsOf(projectId, qualifiedName, type);
        List<MethodInfo> methods = methodsOf(projectId, qualifiedName, classId, filePath, type, fields, references);
        List<String> nestedClassIds = new ArrayList<>();
        List<String> superTypes = superTypesOf(type);

        for (BodyDeclaration<?> member : membersOf(type)) {
            if (member instanceof TypeDeclaration<?> nested) {
                String nestedLocalName = joinOwner(localQualifiedName, nested.getNameAsString());
                String nestedQualified = packageName.isBlank() ? nestedLocalName : packageName + "." + nestedLocalName;
                nestedClassIds.add(projectId + "::" + nestedQualified);
                parseType(projectRoot, projectId, javaFile, packageName, localQualifiedName, nested, classes, references);
            }
        }

        List<String> methodConcepts = methods.stream()
                .flatMap(method -> method.concepts().stream())
                .distinct()
                .toList();
        List<String> concepts = conceptExtractor.merge(
                8,
                conceptExtractor.fromText(qualifiedName, 5),
                methodConcepts);

        classes.add(new ClassInfo(
                classId,
                simpleName,
                qualifiedName,
                kindOf(type),
                packageName,
                filePath,
                beginLine(type),
                endLine(type),
                fields,
                methods,
                nestedClassIds,
                concepts,
                superTypes));
    }

    private List<FieldInfo> fieldsOf(String projectId, String qualifiedName, TypeDeclaration<?> type) {
        String classId = projectId + "::" + qualifiedName;
        return membersOf(type).stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .flatMap(field -> field.getVariables().stream()
                        .map(variable -> new FieldInfo(
                                classId + "#field:" + variable.getNameAsString(),
                                variable.getNameAsString(),
                                variable.getTypeAsString(),
                                beginLine(variable),
                                endLine(variable))))
                .toList();
    }

    private List<MethodInfo> methodsOf(
            String projectId,
            String qualifiedName,
            String classId,
            String filePath,
            TypeDeclaration<?> type,
            List<FieldInfo> fields,
            List<RawCodeReference> references
    ) {
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            String methodId = projectId + "::" + qualifiedName + "#" + method.getNameAsString() + ":" + beginLine(method);
            MethodFacts facts = methodFacts(methodId, classId, filePath, method, fields);
            references.addAll(facts.references());
            List<String> concepts = conceptExtractor.fromText(method.getNameAsString() + " " + method, 6);
            methods.add(new MethodInfo(
                    methodId,
                    method.getNameAsString(),
                    signature(method),
                    method.getTypeAsString(),
                    beginLine(method),
                    endLine(method),
                    facts.calls(),
                    facts.creates(),
                    concepts,
                    "method",
                    parameterTypes(method),
                    facts.callSites(),
                    facts.fieldReads(),
                    facts.fieldWrites(),
                    facts.branchFacts(),
                    facts.exceptionFacts()));
        }
        for (ConstructorDeclaration constructor : type.getConstructors()) {
            String methodId = projectId + "::" + qualifiedName + "#<init>:" + beginLine(constructor);
            MethodFacts facts = methodFacts(methodId, classId, filePath, constructor, fields);
            references.addAll(facts.references());
            String simpleClassName = qualifiedName.contains(".")
                    ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                    : qualifiedName;
            List<String> concepts = conceptExtractor.fromText(simpleClassName + " " + constructor, 6);
            methods.add(new MethodInfo(
                    methodId,
                    simpleClassName,
                    signature(constructor),
                    "constructor",
                    beginLine(constructor),
                    endLine(constructor),
                    facts.calls(),
                    facts.creates(),
                    concepts,
                    "constructor",
                    parameterTypes(constructor),
                    facts.callSites(),
                    facts.fieldReads(),
                    facts.fieldWrites(),
                    facts.branchFacts(),
                    facts.exceptionFacts()));
        }
        methods.sort(Comparator.comparingInt(MethodInfo::beginLine));
        return methods;
    }

    private MethodFacts methodFacts(
            String methodId,
            String classId,
            String filePath,
            CallableDeclaration<?> callable,
            List<FieldInfo> fields
    ) {
        Map<String, FieldInfo> fieldsByName = fields.stream()
                .collect(Collectors.toMap(FieldInfo::name, item -> item, (left, right) -> left, LinkedHashMap::new));
        Set<String> fieldNames = fieldsByName.keySet();
        Set<String> localNames = localNames(callable);
        Set<String> calls = new LinkedHashSet<>();
        Set<String> creates = new LinkedHashSet<>();
        Set<String> fieldReads = new LinkedHashSet<>();
        Set<String> fieldWrites = new LinkedHashSet<>();
        List<CallSiteInfo> callSites = new ArrayList<>();
        List<RawCodeReference> references = new ArrayList<>();

        callable.findAll(MethodCallExpr.class).stream()
                .sorted(Comparator.comparingInt(this::beginLine).thenComparingInt(this::beginColumn))
                .forEach(call -> {
                    String name = call.getNameAsString();
                    ResolvedCall resolved = resolveMethodCall(call);
                    calls.add(name);
                    callSites.add(new CallSiteInfo(
                            "CALL",
                            name,
                            resolved.targetOwner(),
                            resolved.targetSignature(),
                            "",
                            resolved.receiverType(),
                            beginLine(call),
                            resolved.resolved(),
                            call.findAncestor(LambdaExpr.class).isPresent(),
                            argumentTypes(call.getArguments())));
                    references.add(rawReference(
                            methodId,
                            "",
                            "CALL",
                            name,
                            resolved.detail().isBlank() ? callDetail(call) : resolved.detail(),
                            span(filePath, call.getName()),
                            references.size(),
                            resolved.targetOwner(),
                            resolved.targetSignature(),
                            resolved.receiverType(),
                            argumentTypes(call.getArguments()),
                            resolved.resolved(),
                            call.findAncestor(LambdaExpr.class).isPresent()));
                });

        callable.findAll(ObjectCreationExpr.class).stream()
                .sorted(Comparator.comparingInt(this::beginLine).thenComparingInt(this::beginColumn))
                .forEach(creation -> {
                    String typeName = simpleTypeName(creation.getType().asString());
                    ResolvedCall resolved = resolveConstructorCall(creation);
                    creates.add(typeName);
                    callSites.add(new CallSiteInfo(
                            "CREATE",
                            typeName,
                            resolved.targetOwner(),
                            resolved.targetSignature(),
                            "",
                            resolved.receiverType(),
                            beginLine(creation),
                            resolved.resolved(),
                            creation.findAncestor(LambdaExpr.class).isPresent(),
                            argumentTypes(creation.getArguments())));
                    references.add(rawReference(
                            methodId,
                            "",
                            "CREATE",
                            typeName,
                            resolved.detail().isBlank() ? "new " + creation.getTypeAsString() : resolved.detail(),
                            span(filePath, creation.getType()),
                            references.size(),
                            resolved.targetOwner(),
                            resolved.targetSignature(),
                            resolved.receiverType(),
                            argumentTypes(creation.getArguments()),
                            resolved.resolved(),
                            creation.findAncestor(LambdaExpr.class).isPresent()));
                });

        callable.findAll(AssignExpr.class).forEach(assign -> directFieldName(assign.getTarget(), fieldNames, localNames)
                .ifPresent(fieldName -> {
                    fieldWrites.add(fieldName);
                    FieldInfo field = fieldsByName.get(fieldName);
                    references.add(rawReference(methodId, field.id(), "FIELD_WRITE", fieldName,
                            "write via " + assign.getOperator().asString(), span(filePath, fieldNameNode(assign.getTarget())), references.size()));
                }));

        callable.findAll(UnaryExpr.class).forEach(unary -> {
            if (!isMutatingUnary(unary)) {
                return;
            }
            directFieldName(unary.getExpression(), fieldNames, localNames).ifPresent(fieldName -> {
                fieldWrites.add(fieldName);
                FieldInfo field = fieldsByName.get(fieldName);
                references.add(rawReference(methodId, field.id(), "FIELD_WRITE", fieldName,
                        "write via " + unary.getOperator().asString(), span(filePath, fieldNameNode(unary.getExpression())), references.size()));
            });
        });

        callable.findAll(NameExpr.class).stream()
                .filter(name -> fieldNames.contains(name.getNameAsString()))
                .filter(name -> !localNames.contains(name.getNameAsString()))
                .filter(name -> !isWriteTarget(name))
                .sorted(Comparator.comparingInt(this::beginLine).thenComparingInt(this::beginColumn))
                .forEach(name -> {
                    String fieldName = name.getNameAsString();
                    FieldInfo field = fieldsByName.get(fieldName);
                    fieldReads.add(fieldName);
                    references.add(rawReference(methodId, field.id(), "FIELD_READ", fieldName,
                            "read field " + fieldName, span(filePath, name), references.size()));
                });

        callable.findAll(FieldAccessExpr.class).stream()
                .filter(access -> access.getScope() instanceof ThisExpr)
                .filter(access -> fieldNames.contains(access.getNameAsString()))
                .filter(access -> !isWriteTarget(access))
                .sorted(Comparator.comparingInt(this::beginLine).thenComparingInt(this::beginColumn))
                .forEach(access -> {
                    String fieldName = access.getNameAsString();
                    FieldInfo field = fieldsByName.get(fieldName);
                    fieldReads.add(fieldName);
                    references.add(rawReference(methodId, field.id(), "FIELD_READ", fieldName,
                            "read this." + fieldName, span(filePath, access.getName()), references.size()));
                });

        callable.findAll(ReturnStmt.class).stream()
                .sorted(Comparator.comparingInt(this::beginLine).thenComparingInt(this::beginColumn))
                .forEach(returnStmt -> {
                    Node target = returnStmt.getExpression().<Node>map(expression -> expression).orElse(returnStmt);
                    String expression = returnStmt.getExpression().map(Expression::toString).orElse("");
                    references.add(rawReference(methodId, "", "RETURN", expression.isBlank() ? "return" : shortText(expression, 80),
                            expression.isBlank() ? "return" : "return " + shortText(expression, 160),
                            span(filePath, target), references.size()));
                });

        return new MethodFacts(
                List.copyOf(calls),
                List.copyOf(creates),
                List.copyOf(callSites),
                List.copyOf(fieldReads),
                List.copyOf(fieldWrites),
                branchFacts(callable),
                exceptionFacts(callable),
                List.copyOf(references));
    }

    private RawCodeReference rawReference(
            String sourceNodeId,
            String targetNodeId,
            String kind,
            String symbol,
            String detail,
            SourceSpan span,
            int index
    ) {
        return rawReference(sourceNodeId, targetNodeId, kind, symbol, detail, span, index, "", "", "", List.of(), false, false);
    }

    private RawCodeReference rawReference(
            String sourceNodeId,
            String targetNodeId,
            String kind,
            String symbol,
            String detail,
            SourceSpan span,
            int index,
            String targetOwner,
            String targetSignature,
            String receiverType,
            List<String> argumentTypes,
            boolean resolved,
            boolean inLambda
    ) {
        String id = sourceNodeId + ":" + kind + ":" + span.beginLine() + ":" + span.beginColumn() + ":" + index;
        return new RawCodeReference(
                id,
                kind,
                sourceNodeId,
                targetNodeId,
                symbol,
                detail,
                span,
                targetOwner,
                targetSignature,
                receiverType,
                argumentTypes,
                resolved,
                inLambda);
    }

    private List<CodeReference> resolveReferences(
            List<RawCodeReference> rawReferences,
            List<ClassInfo> classes,
            Map<String, List<String>> methodNameIndex
    ) {
        Map<String, ClassInfo> classesByQualifiedName = classes.stream()
                .collect(Collectors.toMap(ClassInfo::qualifiedName, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ClassInfo>> classesBySimpleName = new HashMap<>();
        for (ClassInfo classInfo : classes) {
            classesBySimpleName.computeIfAbsent(classInfo.name(), ignored -> new ArrayList<>()).add(classInfo);
        }

        List<CodeReference> references = new ArrayList<>();
        for (RawCodeReference raw : rawReferences) {
            String targetNodeId = raw.targetNodeId();
            if ((targetNodeId == null || targetNodeId.isBlank()) && "CALL".equals(raw.kind())) {
                targetNodeId = resolveCallTarget(raw, classes, methodNameIndex).orElse("");
            }
            if ((targetNodeId == null || targetNodeId.isBlank()) && "CREATE".equals(raw.kind())) {
                targetNodeId = resolveConstructorTarget(raw, classes).or(() ->
                        resolveCreatedType(raw.symbol(), classesByQualifiedName, classesBySimpleName)).orElse("");
            }
            references.add(new CodeReference(
                    raw.id(),
                    raw.kind(),
                    raw.sourceNodeId(),
                    targetNodeId == null ? "" : targetNodeId,
                    raw.symbol(),
                    raw.detail(),
                    raw.span()));
        }
        return references;
    }

    private Optional<String> resolveCallTarget(RawCodeReference raw, List<ClassInfo> classes, Map<String, List<String>> methodNameIndex) {
        Optional<String> symbolTarget = resolveSymbolTarget(raw, classes, raw.symbol());
        if (symbolTarget.isPresent()) {
            return symbolTarget;
        }
        return resolveCallTarget(raw.sourceNodeId(), raw.symbol(), methodNameIndex);
    }

    private Optional<String> resolveConstructorTarget(RawCodeReference raw, List<ClassInfo> classes) {
        Optional<String> symbolTarget = resolveSymbolTarget(raw, classes, "<init>");
        if (symbolTarget.isPresent()) {
            return symbolTarget;
        }
        return classes.stream()
                .filter(classInfo -> ownersMatch(classInfo.qualifiedName(), raw.targetOwner()) || ownersMatch(classInfo.name(), raw.symbol()))
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> "constructor".equals(method.kind()))
                .filter(method -> method.parameterTypes().size() == raw.argumentTypes().size())
                .map(MethodInfo::id)
                .findFirst();
    }

    private Optional<String> resolveSymbolTarget(RawCodeReference raw, List<ClassInfo> classes, String symbolName) {
        List<String> formalTypes = parameterTypesFromSignature(raw.targetSignature());
        return classes.stream()
                .filter(classInfo -> ownersMatch(classInfo.qualifiedName(), raw.targetOwner()))
                .flatMap(classInfo -> classInfo.methods().stream())
                .filter(method -> {
                    if ("<init>".equals(symbolName)) {
                        return "constructor".equals(method.kind());
                    }
                    return method.name().equals(symbolName);
                })
                .filter(method -> formalTypes.isEmpty() || compatibleParameters(method.parameterTypes(), formalTypes))
                .findFirst()
                .map(MethodInfo::id);
    }

    private Optional<String> resolveCallTarget(String sourceNodeId, String methodName, Map<String, List<String>> methodNameIndex) {
        List<String> targets = methodNameIndex.getOrDefault(methodName, List.of());
        if (targets.size() == 1) {
            return Optional.of(targets.get(0));
        }
        String ownerClassId = sourceNodeId.contains("#") ? sourceNodeId.substring(0, sourceNodeId.indexOf('#')) : "";
        return targets.stream()
                .filter(target -> !ownerClassId.isBlank() && target.startsWith(ownerClassId + "#"))
                .findFirst();
    }

    private Optional<String> resolveCreatedType(
            String typeName,
            Map<String, ClassInfo> classesByQualifiedName,
            Map<String, List<ClassInfo>> classesBySimpleName
    ) {
        ClassInfo qualified = classesByQualifiedName.get(typeName);
        if (qualified != null) {
            return Optional.of(qualified.id());
        }
        List<ClassInfo> simpleMatches = classesBySimpleName.getOrDefault(simpleTypeName(typeName), List.of());
        if (simpleMatches.size() == 1) {
            return Optional.of(simpleMatches.get(0).id());
        }
        return Optional.empty();
    }

    private Map<String, Object> edgeData(CodeReference reference) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("referenceId", reference.id());
        data.put("line", reference.span().beginLine());
        data.put("column", reference.span().beginColumn());
        data.put("detail", reference.detail());
        return data;
    }

    private void addImplementationEdges(
            List<GraphEdge> edges,
            Set<String> edgeIds,
            CodeReference reference,
            List<ClassInfo> classes
    ) {
        ClassInfo targetOwner = ownerOfMethod(classes, reference.targetNodeId()).orElse(null);
        if (targetOwner == null || !"interface".equals(targetOwner.kind())) {
            return;
        }
        for (ClassInfo implementation : classes) {
            if (implementation.id().equals(targetOwner.id())) {
                continue;
            }
            boolean implementsTarget = implementation.superTypes().stream()
                    .anyMatch(superType -> superType.equals(targetOwner.qualifiedName()) || superType.equals(targetOwner.name()));
            if (!implementsTarget) {
                continue;
            }
            implementation.methods().stream()
                    .filter(method -> method.name().equals(reference.symbol()))
                    .findFirst()
                    .ifPresent(method -> {
                        Map<String, Object> data = new LinkedHashMap<>(edgeData(reference));
                        data.put("dispatch", "interface-implementation");
                        data.put("interfaceTarget", reference.targetNodeId());
                        addEdge(edges, edgeIds, reference.sourceNodeId(), method.id(), "CALLS",
                                reference.symbol() + "()", data);
                    });
        }
    }

    private Optional<ClassInfo> ownerOfMethod(List<ClassInfo> classes, String methodId) {
        return classes.stream()
                .filter(classInfo -> classInfo.methods().stream().anyMatch(method -> method.id().equals(methodId)))
                .findFirst();
    }

    private Set<String> localNames(CallableDeclaration<?> callable) {
        Set<String> names = callable.getParameters().stream()
                .map(parameter -> parameter.getNameAsString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        callable.findAll(VariableDeclarator.class).stream()
                .map(variable -> variable.getNameAsString())
                .forEach(names::add);
        return names;
    }

    private Optional<String> directFieldName(Expression expression, Set<String> fieldNames, Set<String> localNames) {
        if (expression instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            if (fieldNames.contains(name) && !localNames.contains(name)) {
                return Optional.of(name);
            }
        }
        if (expression instanceof FieldAccessExpr fieldAccess && fieldAccess.getScope() instanceof ThisExpr) {
            String name = fieldAccess.getNameAsString();
            if (fieldNames.contains(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private Node fieldNameNode(Expression expression) {
        if (expression instanceof FieldAccessExpr fieldAccess) {
            return fieldAccess.getName();
        }
        return expression;
    }

    private boolean isWriteTarget(Node node) {
        Optional<AssignExpr> assignExpr = node.findAncestor(AssignExpr.class);
        if (assignExpr.isPresent() && isAncestorOrSelf(assignExpr.get().getTarget(), node)) {
            return true;
        }
        Optional<UnaryExpr> unaryExpr = node.findAncestor(UnaryExpr.class);
        return unaryExpr.isPresent()
                && isMutatingUnary(unaryExpr.get())
                && isAncestorOrSelf(unaryExpr.get().getExpression(), node);
    }

    private boolean isAncestorOrSelf(Node maybeAncestor, Node node) {
        Node cursor = node;
        while (true) {
            if (cursor == maybeAncestor) {
                return true;
            }
            Optional<Node> parent = cursor.getParentNode();
            if (parent.isEmpty()) {
                return false;
            }
            cursor = parent.get();
        }
    }

    private boolean isMutatingUnary(UnaryExpr unary) {
        return switch (unary.getOperator()) {
            case POSTFIX_DECREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, PREFIX_INCREMENT -> true;
            default -> false;
        };
    }

    private String callDetail(MethodCallExpr call) {
        String args = call.getArguments().stream()
                .map(argument -> shortText(argument.toString(), 40))
                .collect(Collectors.joining(", "));
        return call.getScope().map(scope -> scope + "." + call.getNameAsString() + "(" + args + ")")
                .orElse(call.getNameAsString() + "(" + args + ")");
    }

    private ResolvedCall resolveMethodCall(MethodCallExpr call) {
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            String owner = resolved.declaringType().getQualifiedName();
            String signature = resolved.getName() + "(" + String.join(", ", resolvedParameterTypes(resolved)) + ")";
            String receiverType = call.getScope()
                    .map(this::resolveExpressionType)
                    .orElse(owner);
            return new ResolvedCall(owner, signature, receiverType, true,
                    "receiver=" + receiverType + " target=" + owner + "." + signature);
        } catch (Exception ignored) {
            String receiverType = call.getScope().map(Expression::toString).orElse("");
            return new ResolvedCall("", "", receiverType, false, callDetail(call));
        }
    }

    private ResolvedCall resolveConstructorCall(ObjectCreationExpr creation) {
        try {
            ResolvedConstructorDeclaration resolved = creation.resolve();
            String owner = resolved.declaringType().getQualifiedName();
            String signature = "<init>(" + String.join(", ", resolvedParameterTypes(resolved)) + ")";
            return new ResolvedCall(owner, signature, owner, true,
                    "receiver=" + owner + " target=" + owner + "." + signature);
        } catch (Exception ignored) {
            String typeName = creation.getType().asString();
            return new ResolvedCall(typeName, "", typeName, false, "new " + creation.getTypeAsString());
        }
    }

    private String resolveExpressionType(Expression expression) {
        try {
            return expression.calculateResolvedType().describe();
        } catch (Exception ignored) {
            return expression.toString();
        }
    }

    private List<String> argumentTypes(Iterable<Expression> arguments) {
        List<String> types = new ArrayList<>();
        for (Expression argument : arguments) {
            try {
                ResolvedType resolvedType = argument.calculateResolvedType();
                types.add(resolvedType.describe());
            } catch (Exception ignored) {
                types.add(shortText(argument.toString(), 60));
            }
        }
        return types;
    }

    private List<String> resolvedParameterTypes(ResolvedMethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < method.getNumberOfParams(); i++) {
            types.add(method.getParam(i).getType().describe());
        }
        return types;
    }

    private List<String> resolvedParameterTypes(ResolvedConstructorDeclaration constructor) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < constructor.getNumberOfParams(); i++) {
            types.add(constructor.getParam(i).getType().describe());
        }
        return types;
    }

    private List<String> branchFacts(CallableDeclaration<?> callable) {
        List<String> facts = new ArrayList<>();
        callable.findAll(IfStmt.class).stream()
                .limit(8)
                .map(stmt -> "if@" + beginLine(stmt) + " " + shortText(stmt.getCondition().toString(), 120))
                .forEach(facts::add);
        callable.findAll(SwitchStmt.class).stream()
                .limit(4)
                .map(stmt -> "switch@" + beginLine(stmt) + " " + shortText(stmt.getSelector().toString(), 120))
                .forEach(facts::add);
        return facts.stream().distinct().toList();
    }

    private List<String> exceptionFacts(CallableDeclaration<?> callable) {
        List<String> facts = new ArrayList<>();
        callable.findAll(ThrowStmt.class).stream()
                .limit(6)
                .map(stmt -> "throw@" + beginLine(stmt) + " " + shortText(stmt.getExpression().toString(), 120))
                .forEach(facts::add);
        callable.findAll(CatchClause.class).stream()
                .limit(6)
                .map(stmt -> "catch@" + beginLine(stmt) + " " + stmt.getParameter().getTypeAsString())
                .forEach(facts::add);
        callable.findAll(TryStmt.class).stream()
                .limit(4)
                .map(stmt -> "try@" + beginLine(stmt))
                .forEach(facts::add);
        return facts.stream().distinct().toList();
    }

    private List<BodyDeclaration<?>> membersOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.getMembers();
        }
        if (type instanceof RecordDeclaration declaration) {
            return declaration.getMembers();
        }
        if (type instanceof EnumDeclaration declaration) {
            return declaration.getMembers();
        }
        return List.of();
    }

    private List<String> superTypesOf(TypeDeclaration<?> type) {
        List<String> values = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            declaration.getExtendedTypes().forEach(superType -> values.add(superType.getNameAsString()));
            declaration.getImplementedTypes().forEach(superType -> values.add(superType.getNameAsString()));
        }
        if (type instanceof RecordDeclaration declaration) {
            declaration.getImplementedTypes().forEach(superType -> values.add(superType.getNameAsString()));
        }
        if (type instanceof EnumDeclaration declaration) {
            declaration.getImplementedTypes().forEach(superType -> values.add(superType.getNameAsString()));
        }
        return values.stream().distinct().toList();
    }

    private ProjectSummary summarize(Path projectRoot, String projectId, List<Path> javaFiles, List<ClassInfo> classes) {
        int methodCount = classes.stream().mapToInt(classInfo -> classInfo.methods().size()).sum();
        List<String> packages = classes.stream()
                .map(ClassInfo::packageName)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        List<String> entryPoints = classes.stream()
                .flatMap(classInfo -> classInfo.methods().stream()
                        .filter(method -> method.name().equals("main") || method.name().equals("run"))
                        .map(method -> classInfo.qualifiedName() + "#" + method.name()))
                .limit(12)
                .toList();
        List<String> concepts = conceptExtractor.merge(
                12,
                conceptExtractor.fromText(projectRoot.getFileName().toString(), 6),
                classes.stream().flatMap(classInfo -> classInfo.concepts().stream()).distinct().toList(),
                readReadmeConcepts(projectRoot));

        return new ProjectSummary(
                projectId,
                projectRoot.getFileName().toString(),
                projectRoot.toAbsolutePath().normalize().toString(),
                Files.exists(projectRoot.resolve("pom.xml")) ? "Maven" : "Java",
                javaFiles.size(),
                classes.size(),
                methodCount,
                packages,
                entryPoints,
                concepts);
    }

    private List<ReadingStep> buildReadingPath(List<ClassInfo> classes) {
        AtomicInteger index = new AtomicInteger(1);
        return classes.stream()
                .sorted(Comparator
                        .comparing((ClassInfo classInfo) -> classInfo.methods().stream().anyMatch(method -> method.name().equals("main")) ? 0 : 1)
                        .thenComparing(ClassInfo::qualifiedName))
                .limit(10)
                .map(classInfo -> {
                    MethodInfo firstMethod = classInfo.methods().stream()
                            .filter(method -> method.name().equals("main") || method.name().equals("run"))
                            .findFirst()
                            .orElse(classInfo.methods().stream().findFirst().orElse(null));
                    String target = firstMethod == null ? classInfo.id() : firstMethod.id();
                    int line = firstMethod == null ? classInfo.beginLine() : firstMethod.beginLine();
                    String title = index.getAndIncrement() + ". " + classInfo.name();
                    String description = classInfo.concepts().isEmpty()
                            ? "先看这个类型的字段和公开方法，建立对象边界。"
                            : "关注：" + String.join("、", classInfo.concepts());
                    return new ReadingStep(title, title, description, target, classInfo.filePath(), line);
                })
                .toList();
    }

    private List<String> readReadmeConcepts(Path projectRoot) {
        for (String name : List.of("README.md", "readme.md")) {
            Path readme = projectRoot.resolve(name);
            if (Files.isRegularFile(readme)) {
                try {
                    return conceptExtractor.fromText(Files.readString(readme, StandardCharsets.UTF_8), 10);
                } catch (IOException ignored) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private List<Path> collectJavaFiles(Path projectRoot) {
        try (var stream = Files.walk(projectRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getNameCount() == 0 || path.getName(path.getNameCount() - 1).toString().endsWith(".java"))
                    .filter(path -> StreamSupport.stream(path.spliterator(), false)
                            .noneMatch(segment -> Set.of("target", "build", ".gradle", ".idea").contains(segment.toString())))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private JavaParser parserFor(Path projectRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots(projectRoot)) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(configuration);
    }

    private List<Path> sourceRoots(Path projectRoot) {
        List<Path> roots = List.of(
                        projectRoot.resolve("src/main/java"),
                        projectRoot.resolve("src/test/java"),
                        projectRoot.resolve("src"))
                .stream()
                .filter(Files::isDirectory)
                .distinct()
                .toList();
        return roots.isEmpty() ? List.of(projectRoot) : roots;
    }

    private void addEdge(
            List<GraphEdge> edges,
            Set<String> edgeIds,
            String source,
            String target,
            String type,
            String label,
            Map<String, Object> data
    ) {
        String id = type + ":" + source + "->" + target;
        Object referenceId = data.get("referenceId");
        Object line = data.get("line");
        if (referenceId != null) {
            id += ":" + referenceId;
        } else if (line != null) {
            id += ":" + line;
        }
        if (edgeIds.add(id)) {
            edges.add(new GraphEdge(id, source, target, type, label, data));
        }
    }

    private String signature(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString() + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
        return method.getNameAsString() + "(" + params + ")";
    }

    private String signature(ConstructorDeclaration constructor) {
        String params = constructor.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString() + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
        return constructor.getNameAsString() + "(" + params + ")";
    }

    private List<String> parameterTypes(CallableDeclaration<?> callable) {
        return callable.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .toList();
    }

    private List<String> parameterTypesFromSignature(String signature) {
        if (signature == null || signature.isBlank() || !signature.contains("(") || !signature.endsWith(")")) {
            return List.of();
        }
        String params = signature.substring(signature.indexOf('(') + 1, signature.length() - 1).trim();
        if (params.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(params.split(","))
                .map(String::trim)
                .toList();
    }

    private boolean compatibleParameters(List<String> localTypes, List<String> resolvedTypes) {
        if (localTypes.size() != resolvedTypes.size()) {
            return false;
        }
        for (int i = 0; i < localTypes.size(); i++) {
            String local = simpleTypeName(localTypes.get(i));
            String resolved = simpleTypeName(resolvedTypes.get(i));
            if (!local.equals(resolved) && !localTypes.get(i).equals(resolvedTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean ownersMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equals(right) || simpleTypeName(left).equals(simpleTypeName(right));
    }

    private String kindOf(TypeDeclaration<?> type) {
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
            return "interface";
        }
        return "class";
    }

    private int beginLine(Node node) {
        return node.getBegin().map(position -> position.line).orElse(1);
    }

    private int beginColumn(Node node) {
        return node.getBegin().map(position -> position.column).orElse(1);
    }

    private int endLine(Node node) {
        return node.getEnd().map(position -> position.line).orElse(beginLine(node));
    }

    private int endColumn(Node node) {
        return node.getEnd().map(position -> position.column).orElse(beginColumn(node));
    }

    private SourceSpan span(String filePath, Node node) {
        return new SourceSpan(filePath, beginLine(node), beginColumn(node), endLine(node), endColumn(node));
    }

    private String simpleTypeName(String value) {
        String trimmed = value.trim();
        if (trimmed.contains("<")) {
            trimmed = trimmed.substring(0, trimmed.indexOf('<'));
        }
        if (trimmed.contains(".")) {
            trimmed = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        }
        return trimmed;
    }

    private String shortText(String value, int maxLength) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String joinOwner(String ownerName, String simpleName) {
        return ownerName == null || ownerName.isBlank() ? simpleName : ownerName + "$" + simpleName;
    }

    private record ParsedFile(SourceFile sourceFile, List<ClassInfo> classes, List<RawCodeReference> references) {
    }

    private record MethodFacts(
            List<String> calls,
            List<String> creates,
            List<CallSiteInfo> callSites,
            List<String> fieldReads,
            List<String> fieldWrites,
            List<String> branchFacts,
            List<String> exceptionFacts,
            List<RawCodeReference> references
    ) {
    }

    private record RawCodeReference(
            String id,
            String kind,
            String sourceNodeId,
            String targetNodeId,
            String symbol,
            String detail,
            SourceSpan span,
            String targetOwner,
            String targetSignature,
            String receiverType,
            List<String> argumentTypes,
            boolean resolved,
            boolean inLambda
    ) {
    }

    private record ResolvedCall(
            String targetOwner,
            String targetSignature,
            String receiverType,
            boolean resolved,
            String detail
    ) {
    }

}

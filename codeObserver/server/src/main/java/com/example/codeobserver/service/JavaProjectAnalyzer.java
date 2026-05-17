package com.example.codeobserver.service;

import static com.example.codeobserver.model.WorkspaceModels.ClassInfo;
import static com.example.codeobserver.model.WorkspaceModels.FieldInfo;
import static com.example.codeobserver.model.WorkspaceModels.GraphEdge;
import static com.example.codeobserver.model.WorkspaceModels.GraphNode;
import static com.example.codeobserver.model.WorkspaceModels.MethodInfo;
import static com.example.codeobserver.model.WorkspaceModels.ProjectDetail;
import static com.example.codeobserver.model.WorkspaceModels.ProjectSummary;
import static com.example.codeobserver.model.WorkspaceModels.ReadingStep;
import static com.example.codeobserver.model.WorkspaceModels.SourceFile;

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
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithMembers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;

@Component
public class JavaProjectAnalyzer {
    private final ConceptExtractor conceptExtractor;
    private final JavaParser parser;

    public JavaProjectAnalyzer(ConceptExtractor conceptExtractor) {
        this.conceptExtractor = conceptExtractor;
        this.parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    public ProjectDetail analyze(Path projectRoot, String projectId) {
        List<Path> javaFiles = collectJavaFiles(projectRoot);
        List<SourceFile> sourceFiles = new ArrayList<>();
        List<ClassInfo> classes = new ArrayList<>();
        Map<String, List<String>> methodNameIndex = new HashMap<>();

        for (Path javaFile : javaFiles) {
            parseFile(projectRoot, projectId, javaFile).ifPresent(parsed -> {
                sourceFiles.add(parsed.sourceFile());
                classes.addAll(parsed.classes());
                for (ClassInfo classInfo : parsed.classes()) {
                    for (MethodInfo method : classInfo.methods()) {
                        methodNameIndex.computeIfAbsent(method.name(), ignored -> new ArrayList<>()).add(method.id());
                    }
                }
            });
        }

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
                                "concepts", method.concepts()
                        )));
                addEdge(edges, edgeIds, classInfo.id(), method.id(), "DECLARES", "declares");

                for (String calledName : method.calls()) {
                    List<String> targets = methodNameIndex.getOrDefault(calledName, List.of());
                    List<String> resolvedTargets = targets.size() == 1
                            ? targets
                            : targets.stream().filter(target -> target.startsWith(classInfo.id() + "#")).toList();
                    for (String target : resolvedTargets) {
                        if (!target.equals(method.id())) {
                            addEdge(edges, edgeIds, method.id(), target, "CALLS", calledName + "()");
                        }
                    }
                }

                for (String createdType : method.creates()) {
                    classes.stream()
                            .filter(candidate -> candidate.name().equals(createdType))
                            .findFirst()
                            .ifPresent(target -> addEdge(edges, edgeIds, method.id(), target.id(), "CREATES", "new " + createdType));
                }
            }

            for (String nestedClassId : classInfo.nestedClasses()) {
                addEdge(edges, edgeIds, classInfo.id(), nestedClassId, "CONTAINS", "contains");
            }
        }

        ProjectSummary summary = summarize(projectRoot, projectId, javaFiles, classes);
        return new ProjectDetail(summary, sourceFiles, classes, nodes, edges, buildReadingPath(classes));
    }

    private Optional<ParsedFile> parseFile(Path projectRoot, String projectId, Path javaFile) {
        try {
            CompilationUnit cu = parser.parse(javaFile)
                    .getResult()
                    .orElseThrow(() -> new IllegalArgumentException("Cannot parse " + javaFile));
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
            List<ClassInfo> classes = new ArrayList<>();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                parseType(projectRoot, projectId, javaFile, packageName, "", type, classes);
            }
            SourceFile sourceFile = new SourceFile(
                    javaFile.toAbsolutePath().normalize().toString(),
                    projectRoot.relativize(javaFile).toString(),
                    packageName,
                    classes.stream().map(ClassInfo::qualifiedName).toList());
            return Optional.of(new ParsedFile(sourceFile, classes));
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
            List<ClassInfo> classes
    ) {
        String simpleName = type.getNameAsString();
        String qualifiedName = packageName.isBlank()
                ? joinOwner(ownerName, simpleName)
                : packageName + "." + joinOwner(ownerName, simpleName);
        String classId = projectId + "::" + qualifiedName;
        List<FieldInfo> fields = fieldsOf(type);
        List<MethodInfo> methods = methodsOf(projectId, qualifiedName, type);
        List<String> nestedClassIds = new ArrayList<>();

        for (BodyDeclaration<?> member : membersOf(type)) {
            if (member instanceof TypeDeclaration<?> nested) {
                String nestedQualified = packageName.isBlank()
                        ? joinOwner(joinOwner(ownerName, simpleName), nested.getNameAsString())
                        : packageName + "." + joinOwner(joinOwner(ownerName, simpleName), nested.getNameAsString());
                nestedClassIds.add(projectId + "::" + nestedQualified);
                parseType(projectRoot, projectId, javaFile, packageName, joinOwner(ownerName, simpleName), nested, classes);
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
                javaFile.toAbsolutePath().normalize().toString(),
                beginLine(type),
                endLine(type),
                fields,
                methods,
                nestedClassIds,
                concepts));
    }

    private List<FieldInfo> fieldsOf(TypeDeclaration<?> type) {
        return membersOf(type).stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .flatMap(field -> field.getVariables().stream()
                        .map(variable -> new FieldInfo(variable.getNameAsString(), variable.getTypeAsString())))
                .toList();
    }

    private List<MethodInfo> methodsOf(String projectId, String qualifiedName, TypeDeclaration<?> type) {
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            Set<String> calls = method.findAll(MethodCallExpr.class).stream()
                    .map(MethodCallExpr::getNameAsString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> creates = method.findAll(ObjectCreationExpr.class).stream()
                    .map(ObjectCreationExpr::getType)
                    .map(Type::asString)
                    .map(value -> value.contains(".") ? value.substring(value.lastIndexOf('.') + 1) : value)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            String methodId = projectId + "::" + qualifiedName + "#" + method.getNameAsString() + ":" + beginLine(method);
            List<String> concepts = conceptExtractor.fromText(method.getNameAsString() + " " + method.toString(), 6);
            methods.add(new MethodInfo(
                    methodId,
                    method.getNameAsString(),
                    signature(method),
                    method.getTypeAsString(),
                    beginLine(method),
                    endLine(method),
                    List.copyOf(calls),
                    List.copyOf(creates),
                    concepts));
        }
        methods.sort(Comparator.comparingInt(MethodInfo::beginLine));
        return methods;
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

    private void addEdge(List<GraphEdge> edges, Set<String> edgeIds, String source, String target, String type, String label) {
        String id = type + ":" + source + "->" + target;
        if (edgeIds.add(id)) {
            edges.add(new GraphEdge(id, source, target, type, label));
        }
    }

    private String signature(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString() + " " + parameter.getNameAsString())
                .collect(Collectors.joining(", "));
        return method.getNameAsString() + "(" + params + ")";
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

    private int endLine(Node node) {
        return node.getEnd().map(position -> position.line).orElse(beginLine(node));
    }

    private String joinOwner(String ownerName, String simpleName) {
        return ownerName == null || ownerName.isBlank() ? simpleName : ownerName + "$" + simpleName;
    }

    private record ParsedFile(SourceFile sourceFile, List<ClassInfo> classes) {
    }
}

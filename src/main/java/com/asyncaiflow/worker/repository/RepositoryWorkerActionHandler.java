package com.asyncaiflow.worker.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class RepositoryWorkerActionHandler implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryWorkerActionHandler.class);

    private static final String WORKER_NAME = "repository-worker";

    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of(
            "search_code",
            "read_file",
            "load_code",
            "analyze_module",
            "search_semantic",
            "build_context_pack"
    );

    private static final Set<String> SEMANTIC_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "what", "how",
            "why", "where", "when", "which", "does", "is", "are", "to", "of",
            "in", "on", "at", "a", "an", "请", "帮我", "一下", "查找", "搜索",
            "中", "的", "和", "及", "以及", "一个"
    );

    private static final Set<String> CODE_FILE_EXTENSIONS = Set.of(
            "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "kt", "kts", "scala",
            "c", "h", "cpp", "hpp", "cc", "cs", "php", "rb", "swift", "m", "mm",
            "gd", "sql", "xml", "json", "yaml", "yml", "toml", "ini", "properties",
            "sh", "bash", "zsh", "ps1"
    );

    private static final Set<String> DOCUMENT_HINT_TOKENS = Set.of(
            "doc", "docs", "readme", "guide", "guides", "manual", "architecture", "arch",
            "design", "spec", "specs", "troubleshoot", "faq", "log", "logs"
    );

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final ActionSchemaValidator schemaValidator;
    private final SchemaValidationMode validationMode;
    private final int maxSearchResults;
    private final int maxReadBytes;
    private final Set<String> ignoredDirectoryNames;
    private final int defaultSemanticTopK;
    private final int maxContextFiles;
    private final int maxCharsPerFile;
    private final ZreadMcpClient zreadMcpClient;

    public RepositoryWorkerActionHandler(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            ActionSchemaValidator schemaValidator,
            SchemaValidationMode validationMode,
            int maxSearchResults,
            int maxReadBytes,
            Collection<String> ignoredDirectoryNames) {
        this(
                objectMapper,
                workspaceRoot,
                schemaValidator,
                validationMode,
                maxSearchResults,
                maxReadBytes,
                ignoredDirectoryNames,
                5,
                3,
                4000,
                null);
    }

    public RepositoryWorkerActionHandler(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            ActionSchemaValidator schemaValidator,
            SchemaValidationMode validationMode,
            int maxSearchResults,
            int maxReadBytes,
            Collection<String> ignoredDirectoryNames,
            int defaultSemanticTopK,
            int maxContextFiles,
            int maxCharsPerFile,
            ZreadMcpClient zreadMcpClient) {
        this.objectMapper = objectMapper;
        this.workspaceRoot = workspaceRoot == null
                ? Path.of("").toAbsolutePath().normalize()
                : workspaceRoot.toAbsolutePath().normalize();
        this.schemaValidator = schemaValidator;
        this.validationMode = validationMode == null ? SchemaValidationMode.WARN : validationMode;
        this.maxSearchResults = Math.max(1, maxSearchResults);
        this.maxReadBytes = Math.max(1024, maxReadBytes);
        this.ignoredDirectoryNames = normalizeIgnoredDirectories(ignoredDirectoryNames);
        this.defaultSemanticTopK = clamp(defaultSemanticTopK, 1, this.maxSearchResults);
        this.maxContextFiles = clamp(maxContextFiles, 1, 20);
        this.maxCharsPerFile = clamp(maxCharsPerFile, 200, this.maxReadBytes);
        this.zreadMcpClient = zreadMcpClient;
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!SUPPORTED_ACTION_TYPES.contains(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "Repository worker supports search_code, read_file, load_code, analyze_module, search_semantic and build_context_pack"
            );
        }

        PayloadParseResult payloadParseResult = parsePayload(assignment.payload());
        if (!payloadParseResult.parseable()) {
            LOGGER.warn("schema_validation phase=payload_parse mode={} actionId={} actionType={} errors={}",
                    validationMode, assignment.actionId(), assignment.type(), payloadParseResult.errorMessage());
            return WorkerExecutionResult.failed("invalid payload json", payloadParseResult.errorMessage());
        }

        JsonNode payload = payloadParseResult.payloadNode();
        if (validationMode != SchemaValidationMode.OFF) {
            ActionSchemaValidator.ValidationReport payloadValidation =
                    schemaValidator.validatePayload(assignment.type(), payload);
            WorkerExecutionResult payloadGateResult = handleValidationGate(
                    "payload",
                    assignment,
                    payloadValidation,
                    "payload schema validation failed");
            if (payloadGateResult != null) {
                return payloadGateResult;
            }
        }

        try {
            String resultJson = buildResultJson(assignment.type(), payload);

            if (validationMode != SchemaValidationMode.OFF) {
                ActionSchemaValidator.ValidationReport resultValidation =
                        schemaValidator.validateResult(assignment.type(), resultJson);
                if (!resultValidation.parseable()) {
                    LOGGER.warn("schema_validation phase=result_parse mode={} actionId={} actionType={} errors={}",
                            validationMode, assignment.actionId(), assignment.type(), resultValidation.errorSummary());
                    return WorkerExecutionResult.failed("invalid result json", resultValidation.errorSummary());
                }

                WorkerExecutionResult resultGateResult = handleValidationGate(
                        "result",
                        assignment,
                        resultValidation,
                        "result schema validation failed");
                if (resultGateResult != null) {
                    return resultGateResult;
                }
            }

            LOGGER.info("repository_execution_succeeded actionId={} actionType={} workspaceRoot={}",
                    assignment.actionId(), assignment.type(), workspaceRoot);
            return WorkerExecutionResult.succeeded(resultJson);
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Repository worker failed to execute actionId={} type={}",
                    assignment.actionId(), assignment.type(), exception);
            return WorkerExecutionResult.failed("repository execution failed", exception.getMessage());
        }
    }

    private WorkerExecutionResult handleValidationGate(
            String phase,
            ActionAssignment assignment,
            ActionSchemaValidator.ValidationReport report,
            String strictFailureResult) {
        if (validationMode == SchemaValidationMode.OFF || report.skipped() || report.valid()) {
            return null;
        }

        String schemaPath = report.schemaPath() == null ? "n/a" : report.schemaPath();
        String errors = report.errorSummary();

        if (validationMode == SchemaValidationMode.STRICT) {
            LOGGER.warn("schema_validation phase={} mode={} actionId={} actionType={} schemaPath={} strict=true errors={}",
                    phase, validationMode, assignment.actionId(), assignment.type(), schemaPath, errors);
            return WorkerExecutionResult.failed(strictFailureResult, errors);
        }

        LOGGER.warn("schema_validation phase={} mode={} actionId={} actionType={} schemaPath={} strict=false errors={}",
                phase, validationMode, assignment.actionId(), assignment.type(), schemaPath, errors);
        return null;
    }

    private String buildResultJson(String actionType, JsonNode payload) throws IOException {
        return switch (actionType) {
            case "search_code" -> buildSearchCodeResultJson(payload);
            case "read_file" -> buildReadFileResultJson(payload);
            case "load_code" -> buildLoadCodeResultJson(payload);
            case "analyze_module" -> buildAnalyzeModuleResultJson(payload);
            case "search_semantic" -> buildSearchSemanticResultJson(payload);
            case "build_context_pack" -> buildContextPackResultJson(payload);
            default -> throw new IllegalArgumentException("unsupported action type: " + actionType);
        };
    }

    private String buildSearchCodeResultJson(JsonNode payload) throws IOException {
        String query = firstNonBlank(
                payload.path("query").asText(null),
                payload.path("needle").asText(null));
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_code");
        }

        SearchOutcome outcome = searchCode(query, extractScopePaths(payload));
        ObjectNode result = baseResultNode();
        result.put("query", query);
        result.put("matchCount", outcome.matches().size());
        result.put("truncated", outcome.truncated());

        ArrayNode matches = result.putArray("matches");
        for (SearchMatch match : outcome.matches()) {
            ObjectNode item = matches.addObject();
            item.put("path", match.path());
            item.put("lineNumber", match.lineNumber());
            item.put("lineText", match.lineText());
        }
        return objectMapper.writeValueAsString(result);
    }

    private String buildSearchSemanticResultJson(JsonNode payload) throws IOException {
        String query = firstNonBlank(
                payload.path("query").asText(null),
                payload.path("issue").asText(null),
                payload.path("needle").asText(null));
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required for search_semantic");
        }

        int topK = parseBoundedInt(payload, List.of("topK", "limit"), defaultSemanticTopK, 1, maxSearchResults);
        SemanticSearchOutcome outcome = searchSemantic(query, topK, extractScopePaths(payload), extractRepoPath(payload));

        ObjectNode result = baseResultNode();
        result.put("query", query);
        result.put("topK", topK);
        result.put("engine", outcome.engine());
        result.put("matchCount", outcome.matches().size());
        result.put("truncated", outcome.truncated());

        ArrayNode matches = result.putArray("matches");
        for (SemanticMatch match : outcome.matches()) {
            ObjectNode item = matches.addObject();
            item.put("path", match.path());
            item.put("score", match.score());
            item.put("chunk", match.chunk());
            if (match.lineNumber() != null && match.lineNumber() > 0) {
                item.put("lineNumber", match.lineNumber());
            }
            if (!match.source().isBlank()) {
                item.put("source", match.source());
            }
        }

        return objectMapper.writeValueAsString(result);
    }

    private String buildReadFileResultJson(JsonNode payload) throws IOException {
        ReadFileOutcome outcome = readRequestedFile(payload, true);
        ObjectNode result = baseResultNode();
        result.put("path", outcome.path());
        result.put("lineCount", outcome.lineCount());
        result.put("truncated", outcome.truncated());
        result.put("fileSizeBytes", outcome.fileSizeBytes());
        result.put("content", outcome.content());
        return objectMapper.writeValueAsString(result);
    }

    private String buildLoadCodeResultJson(JsonNode payload) throws IOException {
        int effectiveMaxFiles = parseBoundedInt(payload, List.of("maxFiles"), maxContextFiles, 1, 20);
        int effectiveMaxCharsPerFile = parseBoundedInt(
                payload,
                List.of("maxCharsPerFile"),
                maxCharsPerFile,
                200,
                maxReadBytes);

        LinkedHashSet<String> requestedPaths = new LinkedHashSet<>(extractScopePaths(payload));
        ArrayList<LoadedCodeFile> loadedFiles = new ArrayList<>();
        boolean truncated = false;

        for (String requestedPath : requestedPaths) {
            if (loadedFiles.size() >= effectiveMaxFiles) {
                truncated = true;
                break;
            }

            Path resolved = resolveWorkspacePathSafely(requestedPath);
            if (resolved == null || !Files.isRegularFile(resolved)) {
                continue;
            }

            ReadFileOutcome outcome = readFile(resolved);
            String content = outcome.content();
            boolean fileTruncated = outcome.truncated();
            if (content.length() > effectiveMaxCharsPerFile) {
                content = content.substring(0, effectiveMaxCharsPerFile) + "...";
                fileTruncated = true;
            }

            if (fileTruncated) {
                truncated = true;
            }

            loadedFiles.add(new LoadedCodeFile(
                    outcome.path(),
                    content,
                    outcome.lineCount(),
                    fileTruncated,
                    outcome.fileSizeBytes()));
        }

        ObjectNode result = baseResultNode();
        result.put("requestedPathCount", requestedPaths.size());
        result.put("loadedFileCount", loadedFiles.size());
        result.put("truncated", truncated);
        result.put("code", composeLoadedCode(loadedFiles));

        ArrayNode files = result.putArray("files");
        for (LoadedCodeFile loadedFile : loadedFiles) {
            ObjectNode item = files.addObject();
            item.put("path", loadedFile.path());
            item.put("content", loadedFile.content());
            item.put("lineCount", loadedFile.lineCount());
            item.put("truncated", loadedFile.truncated());
            item.put("fileSizeBytes", loadedFile.fileSizeBytes());
        }

        return objectMapper.writeValueAsString(result);
    }

    private String composeLoadedCode(List<LoadedCodeFile> loadedFiles) {
        if (loadedFiles.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < loadedFiles.size(); index++) {
            LoadedCodeFile loadedFile = loadedFiles.get(index);
            if (index > 0) {
                builder.append("\n\n");
            }
            builder.append("// File: ")
                    .append(loadedFile.path())
                    .append('\n')
                    .append(loadedFile.content());
        }

        return builder.toString();
    }

    private String buildContextPackResultJson(JsonNode payload) throws IOException {
        String issue = firstNonBlank(
                payload.path("issue").asText(null),
                payload.path("query").asText(null));
        if (issue.isBlank()) {
            throw new IllegalArgumentException("issue is required for build_context_pack");
        }

        String retrievalQuery = firstNonBlank(payload.path("query").asText(null), issue);
        int topK = parseBoundedInt(payload, List.of("topK", "limit"), defaultSemanticTopK, 1, maxSearchResults);
        int effectiveMaxFiles = parseBoundedInt(payload, List.of("maxFiles"), maxContextFiles, 1, 20);
        int effectiveMaxCharsPerFile = parseBoundedInt(
                payload,
                List.of("maxCharsPerFile"),
                maxCharsPerFile,
                200,
                maxReadBytes);

        List<SemanticMatch> retrievalMatches = extractRetrievalMatches(payload.path("retrievalResults"));
        String engine = "payload";
        boolean truncated = false;

        if (retrievalMatches.isEmpty()) {
            SemanticSearchOutcome outcome = searchSemantic(
                    retrievalQuery,
                    topK,
                    extractScopePaths(payload),
                    extractRepoPath(payload));
            retrievalMatches = outcome.matches();
            engine = outcome.engine();
            truncated = outcome.truncated();
        }

        LinkedHashSet<String> candidatePaths = new LinkedHashSet<>();
        for (SemanticMatch match : retrievalMatches) {
            if (!match.path().isBlank()) {
                candidatePaths.add(normalizePathText(match.path()));
            }
        }

        String directPath = firstNonBlank(payload.path("path").asText(null), payload.path("file").asText(null));
        if (!directPath.isBlank()) {
            candidatePaths.add(normalizePathText(directPath));
        }

        List<ContextSource> sources = new ArrayList<>();
        for (String candidatePath : candidatePaths) {
            if (sources.size() >= effectiveMaxFiles) {
                break;
            }

            Path resolved = resolveWorkspacePathSafely(candidatePath);
            if (resolved == null || !Files.isRegularFile(resolved)) {
                continue;
            }

            ReadFileOutcome outcome = readFile(resolved);
            String content = outcome.content();
            boolean sourceTruncated = outcome.truncated();
            if (content.length() > effectiveMaxCharsPerFile) {
                content = content.substring(0, effectiveMaxCharsPerFile) + "...";
                sourceTruncated = true;
            }

            double score = resolveBestScoreForPath(candidatePath, retrievalMatches);
            String excerpt = resolveBestExcerptForPath(candidatePath, retrievalMatches);
            sources.add(new ContextSource(
                    outcome.path(),
                    score,
                    content,
                    outcome.lineCount(),
                    sourceTruncated,
                    outcome.fileSizeBytes(),
                    excerpt));
        }

        ObjectNode result = baseResultNode();
        result.put("issue", issue);
        result.put("engine", engine);
        result.put("sourceCount", sources.size());
        result.put("retrievalCount", retrievalMatches.size());
        result.put("truncated", truncated);
        result.put("summary", buildContextPackSummary(sources));

        ArrayNode sourcesNode = result.putArray("sources");
        for (ContextSource source : sources) {
            ObjectNode item = sourcesNode.addObject();
            item.put("file", source.file());
            item.put("score", source.score());
            item.put("content", source.content());
            item.put("lineCount", source.lineCount());
            item.put("truncated", source.truncated());
            item.put("fileSizeBytes", source.fileSizeBytes());
            if (!source.excerpt().isBlank()) {
                item.put("excerpt", source.excerpt());
            }
        }

        ArrayNode retrievalNode = result.putArray("retrieval");
        for (SemanticMatch match : retrievalMatches) {
            ObjectNode item = retrievalNode.addObject();
            item.put("path", match.path());
            item.put("score", match.score());
            item.put("chunk", match.chunk());
            if (match.lineNumber() != null && match.lineNumber() > 0) {
                item.put("lineNumber", match.lineNumber());
            }
            if (!match.source().isBlank()) {
                item.put("source", match.source());
            }
        }

        return objectMapper.writeValueAsString(result);
    }

    private String buildContextPackSummary(List<ContextSource> sources) {
        if (sources.isEmpty()) {
            return "No context source files could be assembled from semantic retrieval.";
        }

        String files = sources.stream()
                .map(ContextSource::file)
                .collect(Collectors.joining(", "));
        return "Context pack assembled from " + sources.size() + " file(s): " + files;
    }

    private String buildAnalyzeModuleResultJson(JsonNode payload) throws IOException {
        ReadFileOutcome outcome = readRequestedFile(payload, false);
        ObjectNode result = baseResultNode();

        String issue = payload.path("issue").asText("");
        if (!issue.isBlank()) {
            result.put("issue", issue);
        }

        String repoContext = firstNonBlank(
                payload.path("repo_context").asText(null),
                payload.path("context").asText(null));
        if (!repoContext.isBlank()) {
            result.put("repoContext", repoContext);
        }

        if (outcome.exists()) {
            result.put("path", outcome.path());
            result.put("lineCount", outcome.lineCount());
            result.put("truncated", outcome.truncated());
            result.put("fileSizeBytes", outcome.fileSizeBytes());
            result.put("content", outcome.content());
            result.put("note", "analyze_module compatibility path executed through repository file read");
        } else {
            result.putNull("path");
            result.put("lineCount", 0);
            result.put("truncated", false);
            result.put("fileSizeBytes", 0L);
            result.put("content", "");
            result.put("note", "No file path provided; analyze_module completed with metadata-only context");
        }

        return objectMapper.writeValueAsString(result);
    }

    private ObjectNode baseResultNode() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", WORKER_NAME);
        return result;
    }

    private SearchOutcome searchCode(String query, List<String> requestedPaths) throws IOException {
        List<SearchMatch> matches = new ArrayList<>();
        boolean truncated = false;
        String normalizedQuery = query.toLowerCase(Locale.ROOT);

        for (Path searchRoot : resolveSearchRoots(requestedPaths)) {
            if (Files.isRegularFile(searchRoot)) {
                if (searchFile(searchRoot, normalizedQuery, matches)) {
                    truncated = true;
                    break;
                }
                continue;
            }

            if (!Files.isDirectory(searchRoot)) {
                continue;
            }

            SearchFileVisitor visitor = new SearchFileVisitor(normalizedQuery, matches);
            Files.walkFileTree(searchRoot, visitor);
            if (visitor.truncated()) {
                truncated = true;
                break;
            }
        }

        return new SearchOutcome(List.copyOf(matches), truncated);
    }

    private SemanticSearchOutcome searchSemantic(
            String query,
            int topK,
            List<String> requestedPaths,
            String repoPath) throws IOException {
        if (zreadMcpClient != null && zreadMcpClient.enabled()) {
            var searchResult = zreadMcpClient.search(query, topK, requestedPaths, repoPath);
            if (searchResult.isPresent()) {
                List<SemanticMatch> matches = new ArrayList<>();
                for (ZreadMcpClient.Match match : searchResult.get().matches()) {
                    String path = normalizePathText(match.path());
                    if (path.isBlank()) {
                        continue;
                    }
                    String chunk = abbreviate(firstNonBlank(match.chunk()), 320);
                    matches.add(new SemanticMatch(
                            path,
                            roundScore(match.score()),
                            chunk,
                            match.lineNumber(),
                            "zread:" + searchResult.get().toolName()));
                }

                matches.sort((left, right) -> {
                    int scoreCompare = Double.compare(right.score(), left.score());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return left.path().compareTo(right.path());
                });

                if (!matches.isEmpty()) {
                    boolean truncated = matches.size() > topK;
                    List<SemanticMatch> selected = truncated
                            ? List.copyOf(matches.subList(0, topK))
                            : List.copyOf(matches);
                    return new SemanticSearchOutcome(selected, truncated, "zread_mcp");
                }
            }
        }

        return localSemanticSearch(query, topK, requestedPaths);
    }

    private SemanticSearchOutcome localSemanticSearch(
            String query,
            int topK,
            List<String> requestedPaths) throws IOException {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<String> tokens = buildSemanticTokens(query);
        boolean preferCodeResults = shouldPreferCodeResults(normalizedQuery, requestedPaths);
        if (tokens.isEmpty() && !normalizedQuery.isBlank()) {
            tokens = List.of(normalizedQuery);
        }

        List<SemanticMatch> matches = new ArrayList<>();
        for (Path searchRoot : resolveSearchRoots(requestedPaths)) {
            if (Files.isRegularFile(searchRoot)) {
                evaluateSemanticFile(searchRoot, normalizedQuery, tokens, matches, preferCodeResults);
                continue;
            }

            if (!Files.isDirectory(searchRoot)) {
                continue;
            }

            SemanticSearchFileVisitor visitor = new SemanticSearchFileVisitor(
                    normalizedQuery,
                    tokens,
                    matches,
                    preferCodeResults);
            Files.walkFileTree(searchRoot, visitor);
        }

        matches.sort((left, right) -> {
            int scoreCompare = Double.compare(right.score(), left.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return left.path().compareTo(right.path());
        });

        boolean truncated = matches.size() > topK;
        List<SemanticMatch> selected = truncated
                ? List.copyOf(matches.subList(0, topK))
                : List.copyOf(matches);

        return new SemanticSearchOutcome(selected, truncated, "local_fallback");
    }

    private void evaluateSemanticFile(
            Path file,
            String normalizedQuery,
            List<String> tokens,
            List<SemanticMatch> matches,
            boolean preferCodeResults) {
        if (preferCodeResults && shouldSkipNonCodeSemanticFile(file)) {
            return;
        }

        try {
            if (Files.size(file) > maxReadBytes) {
                return;
            }
        } catch (IOException exception) {
            LOGGER.debug("Skipping unreadable file during semantic search: {}", file, exception);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            double bestScore = 0.0D;
            int bestLineNumber = 0;
            String bestLine = "";

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String normalizedLine = line.toLowerCase(Locale.ROOT);
                double score = semanticLineScore(normalizedLine, normalizedQuery, tokens);
                if (score <= bestScore) {
                    continue;
                }
                bestScore = score;
                bestLineNumber = lineNumber;
                bestLine = line;
            }

            if (bestScore <= 0.0D) {
                return;
            }

            String relativePath = relativePathString(file);
            double pathScore = semanticPathScore(relativePath.toLowerCase(Locale.ROOT), normalizedQuery, tokens);
            matches.add(new SemanticMatch(
                    relativePath,
                    roundScore(bestScore + pathScore),
                    abbreviate(bestLine.trim(), 280),
                    bestLineNumber,
                    "local_semantic"));
        } catch (IOException exception) {
            LOGGER.debug("Skipping file during semantic search because it could not be decoded as text: {}", file, exception);
        }
    }

    private double semanticLineScore(String normalizedLine, String normalizedQuery, List<String> tokens) {
        double score = 0.0D;

        if (!normalizedQuery.isBlank() && normalizedLine.contains(normalizedQuery)) {
            score += 8.0D;
        }

        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            if (!normalizedLine.contains(token)) {
                continue;
            }

            if (containsCjk(token)) {
                score += 1.4D;
            } else {
                score += Math.min(2.6D, 0.7D + token.length() * 0.25D);
            }
        }

        return score;
    }

    private double semanticPathScore(String normalizedPath, String normalizedQuery, List<String> tokens) {
        double score = 0.0D;

        if (!normalizedQuery.isBlank() && normalizedPath.contains(normalizedQuery)) {
            score += 2.0D;
        }

        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            if (normalizedPath.contains(token)) {
                score += 0.4D;
            }
        }

        return score;
    }

    private boolean shouldPreferCodeResults(String normalizedQuery, List<String> requestedPaths) {
        if (!normalizedQuery.isBlank()) {
            for (String hint : DOCUMENT_HINT_TOKENS) {
                if (normalizedQuery.contains(hint)) {
                    return false;
                }
            }
        }

        for (String path : requestedPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }

            String normalizedPath = normalizePathText(path).toLowerCase(Locale.ROOT);
                if (normalizedPath.equals("docs")
                    || normalizedPath.equals("logs")
                    || normalizedPath.equals(".github")
                    || normalizedPath.startsWith("docs/")
                    || normalizedPath.contains("/docs/")
                    || normalizedPath.startsWith("logs/")
                    || normalizedPath.contains("/logs/")
                    || normalizedPath.startsWith(".github/")
                    || normalizedPath.contains("/.github/")
                    || normalizedPath.endsWith(".md")
                    || normalizedPath.endsWith(".rst")
                    || normalizedPath.endsWith(".txt")) {
                return false;
            }
        }

        return true;
    }

    private boolean shouldSkipNonCodeSemanticFile(Path file) {
        String relativePath = relativePathString(file).toLowerCase(Locale.ROOT);
        if (relativePath.startsWith("docs/")
                || relativePath.contains("/docs/")
                || relativePath.startsWith("logs/")
                || relativePath.contains("/logs/")
                || relativePath.startsWith(".github/")
                || relativePath.contains("/.github/")) {
            return true;
        }

        int extensionIndex = relativePath.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == relativePath.length() - 1) {
            return true;
        }

        String extension = relativePath.substring(extensionIndex + 1);
        return !CODE_FILE_EXTENSIONS.contains(extension);
    }

    private List<String> buildSemanticTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT).trim();

        for (String part : normalized.split("[\\s\\p{Punct}]+")) {
            addTokenVariants(tokens, part);
        }

        if (tokens.isEmpty()) {
            addTokenVariants(tokens, normalized);
        }

        return tokens.stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !SEMANTIC_STOP_WORDS.contains(token))
                .limit(24)
                .toList();
    }

    private void addTokenVariants(Set<String> tokens, String rawToken) {
        if (rawToken == null) {
            return;
        }

        String token = rawToken.trim();
        if (token.isBlank()) {
            return;
        }

        tokens.add(token);
        if (!containsCjk(token) || token.length() < 3) {
            return;
        }

        for (int i = 0; i <= token.length() - 2; i++) {
            tokens.add(token.substring(i, i + 2));
        }

        if (token.length() >= 4) {
            for (int i = 0; i <= token.length() - 3; i++) {
                tokens.add(token.substring(i, i + 3));
            }
        }
    }

    private boolean searchFile(Path file, String normalizedQuery, List<SearchMatch> matches) {
        try {
            if (Files.size(file) > maxReadBytes) {
                return false;
            }
        } catch (IOException exception) {
            LOGGER.debug("Skipping unreadable file during search: {}", file, exception);
            return false;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    continue;
                }

                matches.add(new SearchMatch(
                        relativePathString(file),
                        lineNumber,
                        abbreviate(line.trim(), 240)));
                if (matches.size() >= maxSearchResults) {
                    return true;
                }
            }
        } catch (IOException exception) {
            LOGGER.debug("Skipping file during search because it could not be decoded as text: {}", file, exception);
        }

        return false;
    }

    private ReadFileOutcome readRequestedFile(JsonNode payload, boolean requirePath) throws IOException {
        String rawPath = firstNonBlank(
                payload.path("path").asText(null),
                payload.path("file").asText(null));

        if (rawPath.isBlank()) {
            if (requirePath) {
                throw new IllegalArgumentException("path or file is required");
            }
            return ReadFileOutcome.empty();
        }

        Path resolved = resolveWorkspacePath(rawPath);
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("file not found: " + rawPath);
        }

        return readFile(resolved);
    }

    private ReadFileOutcome readFile(Path file) throws IOException {
        long fileSize = Files.size(file);
        boolean truncated = fileSize > maxReadBytes;
        byte[] bytes;

        if (truncated) {
            try (InputStream inputStream = Files.newInputStream(file)) {
                bytes = inputStream.readNBytes(maxReadBytes);
            }
        } else {
            bytes = Files.readAllBytes(file);
        }

        if (containsBinaryContent(bytes)) {
            throw new IllegalArgumentException("binary files are not supported: " + relativePathString(file));
        }

        String content = new String(bytes, StandardCharsets.UTF_8);
        return new ReadFileOutcome(
                true,
                relativePathString(file),
                content,
                countLines(content),
                truncated,
                fileSize);
    }

    private List<Path> resolveSearchRoots(List<String> requestedPaths) {
        if (requestedPaths.isEmpty()) {
            return List.of(workspaceRoot);
        }

        LinkedHashSet<Path> resolvedRoots = new LinkedHashSet<>();
        for (String requestedPath : requestedPaths) {
            if (requestedPath == null || requestedPath.isBlank()) {
                continue;
            }

            Path resolved = resolveWorkspacePath(requestedPath);
            if (Files.exists(resolved)) {
                resolvedRoots.add(resolved);
            }
        }
        return resolvedRoots.isEmpty() ? List.of(workspaceRoot) : List.copyOf(resolvedRoots);
    }

    private List<String> extractScopePaths(JsonNode payload) {
        ArrayList<String> paths = new ArrayList<>();
        collectTextArray(payload.path("scope").path("paths"), paths);
        collectTextArray(payload.path("paths"), paths);
        String directPath = firstNonBlank(payload.path("path").asText(null), payload.path("file").asText(null));
        if (!directPath.isBlank()) {
            paths.add(directPath);
        }
        return paths.stream().distinct().toList();
    }

    private String extractRepoPath(JsonNode payload) {
        return firstNonBlank(
                payload.path("scope").path("repoPath").asText(null),
                payload.path("repoPath").asText(null),
                workspaceRoot.toString());
    }

    private List<SemanticMatch> extractRetrievalMatches(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        ArrayList<SemanticMatch> matches = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }

            String path = normalizePathText(firstNonBlank(
                    item.path("path").asText(null),
                    item.path("file").asText(null),
                    item.path("filePath").asText(null)));
            if (path.isBlank()) {
                continue;
            }

            double score = parseDouble(item.path("score"), 0.0D);
            String chunk = firstNonBlank(
                    item.path("chunk").asText(null),
                    item.path("snippet").asText(null),
                    item.path("lineText").asText(null));
            Integer lineNumber = parseInteger(item.path("lineNumber"));
            if (lineNumber == null) {
                lineNumber = parseInteger(item.path("line"));
            }
            String source = firstNonBlank(item.path("source").asText(null), "payload");

            matches.add(new SemanticMatch(path, roundScore(score), abbreviate(chunk, 320), lineNumber, source));
        }

        matches.sort((left, right) -> {
            int scoreCompare = Double.compare(right.score(), left.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return left.path().compareTo(right.path());
        });

        return List.copyOf(matches);
    }

    private double resolveBestScoreForPath(String path, List<SemanticMatch> matches) {
        String normalizedTarget = normalizePathText(path);
        double score = 0.0D;

        for (SemanticMatch match : matches) {
            if (!normalizePathText(match.path()).equals(normalizedTarget)) {
                continue;
            }
            score = Math.max(score, match.score());
        }

        return roundScore(score);
    }

    private String resolveBestExcerptForPath(String path, List<SemanticMatch> matches) {
        String normalizedTarget = normalizePathText(path);
        for (SemanticMatch match : matches) {
            if (normalizePathText(match.path()).equals(normalizedTarget) && !match.chunk().isBlank()) {
                return match.chunk();
            }
        }
        return "";
    }

    private void collectTextArray(JsonNode node, List<String> target) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                target.add(item.asText());
            }
        }
    }

    private int parseBoundedInt(
            JsonNode payload,
            List<String> fieldNames,
            int defaultValue,
            int min,
            int max) {
        for (String fieldName : fieldNames) {
            JsonNode valueNode = payload.get(fieldName);
            Integer value = parseInteger(valueNode);
            if (value != null) {
                return clamp(value, min, max);
            }
        }
        return clamp(defaultValue, min, max);
    }

    private static Integer parseInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (!node.isTextual()) {
            return null;
        }

        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double parseDouble(JsonNode node, double defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Path resolveWorkspacePath(String rawPath) {
        try {
            Path candidate = Paths.get(rawPath.trim());
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : workspaceRoot.resolve(candidate).normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("path must stay within workspace root: " + rawPath);
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("invalid path: " + rawPath, exception);
        }
    }

    private Path resolveWorkspacePathSafely(String rawPath) {
        try {
            return resolveWorkspacePath(rawPath);
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("Skipping out-of-workspace path {} during context build", rawPath);
            return null;
        }
    }

    private String normalizePathText(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }

        String trimmed = rawPath.trim();
        try {
            Path candidate = Paths.get(trimmed);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : workspaceRoot.resolve(candidate).normalize();
            if (resolved.startsWith(workspaceRoot)) {
                return relativePathString(resolved);
            }
            return trimmed.replace('\\', '/');
        } catch (InvalidPathException ignored) {
            return trimmed.replace('\\', '/');
        }
    }

    private String relativePathString(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private boolean shouldSkipDirectory(Path directory) {
        if (directory == null || directory.equals(workspaceRoot)) {
            return false;
        }
        Path fileName = directory.getFileName();
        return fileName != null && ignoredDirectoryNames.contains(fileName.toString());
    }

    private Set<String> normalizeIgnoredDirectories(Collection<String> directories) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (directories != null) {
            for (String directory : directories) {
                if (directory != null && !directory.isBlank()) {
                    normalized.add(directory.trim());
                }
            }
        }
        return Set.copyOf(normalized);
    }

    private PayloadParseResult parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return PayloadParseResult.success(objectMapper.createObjectNode());
        }

        try {
            return PayloadParseResult.success(objectMapper.readTree(payload));
        } catch (IOException exception) {
            return PayloadParseResult.failure("payload JSON parse failed: " + exception.getMessage());
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, maxLength)) + "...";
    }

    private static boolean containsBinaryContent(byte[] bytes) {
        for (byte current : bytes) {
            if (current == 0) {
                return true;
            }
        }
        return false;
    }

    private static int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return (int) content.lines().count();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double roundScore(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private static boolean containsCjk(String value) {
        for (int index = 0; index < value.length(); index++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(index));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private record PayloadParseResult(boolean parseable, JsonNode payloadNode, String errorMessage) {

        static PayloadParseResult success(JsonNode payloadNode) {
            return new PayloadParseResult(true, payloadNode, null);
        }

        static PayloadParseResult failure(String errorMessage) {
            return new PayloadParseResult(false, null, errorMessage);
        }
    }

    private record SearchMatch(String path, int lineNumber, String lineText) {
    }

    private record SearchOutcome(List<SearchMatch> matches, boolean truncated) {
    }

    private record SemanticMatch(String path, double score, String chunk, Integer lineNumber, String source) {
    }

    private record SemanticSearchOutcome(List<SemanticMatch> matches, boolean truncated, String engine) {
    }

    private record ContextSource(
            String file,
            double score,
            String content,
            int lineCount,
            boolean truncated,
            long fileSizeBytes,
            String excerpt
    ) {
    }

            private record LoadedCodeFile(
                String path,
                String content,
                int lineCount,
                boolean truncated,
                long fileSizeBytes
            ) {
            }

    private record ReadFileOutcome(
            boolean exists,
            String path,
            String content,
            int lineCount,
            boolean truncated,
            long fileSizeBytes
    ) {
        static ReadFileOutcome empty() {
            return new ReadFileOutcome(false, "", "", 0, false, 0L);
        }
    }

    private final class SearchFileVisitor extends SimpleFileVisitor<Path> {

        private final String normalizedQuery;
        private final List<SearchMatch> matches;
        private boolean truncated;

        private SearchFileVisitor(String normalizedQuery, List<SearchMatch> matches) {
            this.normalizedQuery = normalizedQuery;
            this.matches = matches;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return shouldSkipDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }

            if (searchFile(file, normalizedQuery, matches)) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        boolean truncated() {
            return truncated;
        }
    }

    private final class SemanticSearchFileVisitor extends SimpleFileVisitor<Path> {

        private final String normalizedQuery;
        private final List<String> tokens;
        private final List<SemanticMatch> matches;
        private final boolean preferCodeResults;

        private SemanticSearchFileVisitor(
                String normalizedQuery,
                List<String> tokens,
                List<SemanticMatch> matches,
                boolean preferCodeResults) {
            this.normalizedQuery = normalizedQuery;
            this.tokens = tokens;
            this.matches = matches;
            this.preferCodeResults = preferCodeResults;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return shouldSkipDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }

            evaluateSemanticFile(file, normalizedQuery, tokens, matches, preferCodeResults);
            return FileVisitResult.CONTINUE;
        }
    }
}

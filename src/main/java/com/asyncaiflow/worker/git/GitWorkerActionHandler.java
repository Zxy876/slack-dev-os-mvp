package com.asyncaiflow.worker.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

public class GitWorkerActionHandler implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitWorkerActionHandler.class);

    private static final String WORKER_NAME = "git-worker";

    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of(
            "create_branch",
            "apply_patch",
            "commit_changes",
            "drift_progress_push"
    );

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final ActionSchemaValidator schemaValidator;
    private final SchemaValidationMode validationMode;
    private final int maxPatchBytes;
    private final int maxCommandOutputBytes;
    private final Duration commandTimeout;

    public GitWorkerActionHandler(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            ActionSchemaValidator schemaValidator,
            SchemaValidationMode validationMode,
            int maxPatchBytes,
            int maxCommandOutputBytes,
            long commandTimeoutMillis) {
        this.objectMapper = objectMapper;
        this.workspaceRoot = normalizeWorkspaceRoot(workspaceRoot);
        this.schemaValidator = schemaValidator;
        this.validationMode = validationMode == null ? SchemaValidationMode.WARN : validationMode;
        this.maxPatchBytes = Math.max(1024, maxPatchBytes);
        this.maxCommandOutputBytes = Math.max(4096, maxCommandOutputBytes);
        this.commandTimeout = Duration.ofMillis(Math.max(1000L, commandTimeoutMillis));
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!SUPPORTED_ACTION_TYPES.contains(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "Git worker supports create_branch, apply_patch, commit_changes and drift_progress_push"
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

            LOGGER.info("git_execution_succeeded actionId={} actionType={} workspaceRoot={}",
                    assignment.actionId(), assignment.type(), workspaceRoot);
            return WorkerExecutionResult.succeeded(resultJson);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Git worker interrupted actionId={} type={} workspaceRoot={}",
                    assignment.actionId(), assignment.type(), workspaceRoot, exception);
            return WorkerExecutionResult.failed("git execution interrupted", exception.getMessage());
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Git worker failed actionId={} type={} workspaceRoot={}",
                    assignment.actionId(), assignment.type(), workspaceRoot, exception);
            return WorkerExecutionResult.failed("git execution failed", exception.getMessage());
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

    private String buildResultJson(String actionType, JsonNode payload) throws IOException, InterruptedException {
        return switch (actionType) {
            case "create_branch" -> buildCreateBranchResultJson(payload);
            case "apply_patch" -> buildApplyPatchResultJson(payload);
            case "commit_changes" -> buildCommitChangesResultJson(payload);
            case "drift_progress_push" -> buildDriftProgressPushResultJson(payload);
            default -> throw new IllegalArgumentException("unsupported action type: " + actionType);
        };
    }

    // ── drift_progress_push ──────────────────────────────────────────────────
    // Creates a branch, stages all changes, commits, and pushes to origin.
    // Payload fields:
    //   branch_prefix  (string, required) — e.g. "drift/hackathon"
    //   issue_text     (string, optional) — used in commit message
    //   player_id      (string, optional) — used in commit message
    // ────────────────────────────────────────────────────────────────────────
    private String buildDriftProgressPushResultJson(JsonNode payload) throws IOException, InterruptedException {
        Path repoRoot = resolveRepoRoot(payload);

        String branchPrefix = firstNonBlank(payload.path("branch_prefix").asText(null), "drift/session");
        // Sanitise: replace spaces and colons (unsafe in branch names)
        long epochSeconds = System.currentTimeMillis() / 1000L;
        String branchName = (branchPrefix + "/" + epochSeconds)
                .replaceAll("[^a-zA-Z0-9/_.-]", "-");

        String issueText = firstNonBlank(payload.path("issue_text").asText(null), "drift session");
        String playerId = firstNonBlank(payload.path("player_id").asText(null), "unknown");

        // 1. Create + checkout branch
        runGitChecked(repoRoot, List.of("checkout", "-B", branchName), "failed to create drift branch");

        // 2. Stage all
        runGitChecked(repoRoot, List.of("add", "-A"), "failed to stage changes");

        // 3. Commit (allow empty so the branch always exists even with clean tree)
        String commitMessage = String.format(
                "[drift] player=%s issue=%s",
                playerId,
                abbreviate(issueText, 120));
        runGitChecked(repoRoot,
                List.of("commit", "--allow-empty", "-m", commitMessage),
                "failed to commit drift progress");

        String commitHash = runGitChecked(repoRoot, List.of("rev-parse", "HEAD"), "failed to resolve HEAD").output();

        // 4. Push to origin (best-effort; failure is logged but not fatal)
        String pushOutput;
        try {
            CommandResult pushResult = runGitChecked(repoRoot,
                    List.of("push", "--set-upstream", "origin", branchName),
                    "git push failed");
            pushOutput = pushResult.output();
        } catch (IllegalStateException ex) {
            LOGGER.warn("drift_progress_push push step failed (non-fatal): {}", ex.getMessage());
            pushOutput = "push_skipped: " + ex.getMessage();
        }

        ObjectNode result = baseResultNode();
        result.put("repoPath", relativePathString(repoRoot));
        result.put("branch", branchName);
        result.put("commitHash", commitHash);
        result.put("commitMessage", commitMessage);
        result.put("pushOutput", abbreviate(pushOutput, 400));
        result.put("playerId", playerId);

        return objectMapper.writeValueAsString(result);
    }

    private String buildCreateBranchResultJson(JsonNode payload) throws IOException, InterruptedException {
        Path repoRoot = resolveRepoRoot(payload);
        String branchName = firstNonBlank(payload.path("branchName").asText(null));
        if (branchName.isBlank()) {
            throw new IllegalArgumentException("branchName is required for create_branch");
        }

        String fromRef = firstNonBlank(payload.path("fromRef").asText(null), "HEAD");
        boolean checkout = parseBoolean(payload.get("checkout"), true);
        boolean force = parseBoolean(payload.get("force"), false);

        runGitChecked(repoRoot, List.of("check-ref-format", "--branch", branchName), "invalid branch name");

        String previousBranch = currentBranch(repoRoot);
        boolean exists = runGit(repoRoot, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branchName))
                .exitCode() == 0;

        boolean created = !exists;
        if (exists && !force) {
            throw new IllegalStateException("branch already exists: " + branchName);
        }

        if (exists) {
            runGitChecked(repoRoot, List.of("branch", "-f", branchName, fromRef), "failed to force-update branch");
        } else {
            runGitChecked(repoRoot, List.of("branch", branchName, fromRef), "failed to create branch");
        }

        if (checkout) {
            runGitChecked(repoRoot, List.of("checkout", branchName), "failed to checkout branch");
        }

        ObjectNode result = baseResultNode();
        result.put("repoPath", relativePathString(repoRoot));
        result.put("branchName", branchName);
        result.put("fromRef", fromRef);
        result.put("previousBranch", previousBranch);
        result.put("currentBranch", currentBranch(repoRoot));
        result.put("created", created);
        result.put("checkedOut", checkout);
        return objectMapper.writeValueAsString(result);
    }

    private String buildApplyPatchResultJson(JsonNode payload) throws IOException, InterruptedException {
        Path repoRoot = resolveRepoRoot(payload);
        String patch = firstNonBlank(payload.path("patch").asText(null));
        if (patch.isBlank()) {
            throw new IllegalArgumentException("patch is required for apply_patch");
        }

        int patchBytes = patch.getBytes(StandardCharsets.UTF_8).length;
        if (patchBytes > maxPatchBytes) {
            throw new IllegalArgumentException("patch exceeds maxPatchBytes limit: " + maxPatchBytes);
        }

        int strip = parseInt(payload.get("strip"), 1, 0, 8);
        boolean cached = parseBoolean(payload.get("cached"), false);
        boolean threeWay = parseBoolean(payload.get("threeWay"), true);

        String patchContent = patch.endsWith("\n") ? patch : patch + "\n";
        Path tempPatchFile = Files.createTempFile("asyncaiflow-git-worker-", ".patch");
        Files.writeString(tempPatchFile, patchContent, StandardCharsets.UTF_8);

        try {
            applyPatchWithStripFallback(repoRoot, tempPatchFile, strip, threeWay, cached);

            List<String> stagedFiles = parseLines(
                    runGitChecked(repoRoot, List.of("diff", "--cached", "--name-only"),
                            "failed to inspect staged files").output());
            List<String> workingFiles = parseLines(
                    runGitChecked(repoRoot, List.of("diff", "--name-only"),
                            "failed to inspect working tree files").output());

            LinkedHashSet<String> changedFiles = new LinkedHashSet<>(extractChangedFilesFromPatch(patch));
            changedFiles.addAll(stagedFiles);
            changedFiles.addAll(workingFiles);

            ObjectNode result = baseResultNode();
            result.put("repoPath", relativePathString(repoRoot));
            result.put("applied", true);
            result.put("cached", cached);
            result.put("threeWay", threeWay);
            result.put("changedFileCount", changedFiles.size());
            result.put("stagedFileCount", stagedFiles.size());
            result.put("workingFileCount", workingFiles.size());

            ArrayNode changedArray = result.putArray("changedFiles");
            for (String changedFile : changedFiles) {
                changedArray.add(changedFile);
            }

            return objectMapper.writeValueAsString(result);
        } finally {
            Files.deleteIfExists(tempPatchFile);
        }
    }

    private void applyPatchWithStripFallback(
            Path repoRoot,
            Path patchFile,
            int configuredStrip,
            boolean threeWay,
            boolean cached) throws IOException, InterruptedException {
        List<Integer> stripCandidates = buildStripCandidates(repoRoot, configuredStrip);
        String lastPathMismatchDetail = "";

        for (int stripCandidate : stripCandidates) {
            CommandResult applyResult = runGit(repoRoot, buildApplyPatchArgs(patchFile, stripCandidate, threeWay, cached));
            if (applyResult.exitCode() == 0) {
                if (stripCandidate != configuredStrip) {
                    LOGGER.info(
                            "apply_patch strip fallback succeeded repoPath={} configuredStrip={} effectiveStrip={}",
                            relativePathString(repoRoot),
                            configuredStrip,
                            stripCandidate);
                }
                return;
            }

            String detail = firstNonBlank(applyResult.output(), "exitCode=" + applyResult.exitCode());
            if (!isLikelyStripMismatch(detail)) {
                throw new IllegalStateException("failed to apply patch: " + detail);
            }

            lastPathMismatchDetail = detail;
            LOGGER.debug(
                    "apply_patch strip candidate failed repoPath={} strip={} detail={}",
                    relativePathString(repoRoot),
                    stripCandidate,
                    abbreviate(detail, 280));
        }

        throw new IllegalStateException("failed to apply patch: "
                + firstNonBlank(lastPathMismatchDetail, "path mismatch between patch and repository root"));
    }

    private List<String> buildApplyPatchArgs(Path patchFile, int strip, boolean threeWay, boolean cached) {
        ArrayList<String> args = new ArrayList<>();
        args.add("apply");
        args.add("--recount");
        args.add("--whitespace=nowarn");
        args.add("-p" + strip);
        if (threeWay) {
            args.add("--3way");
        }
        if (cached) {
            args.add("--cached");
        }
        args.add(patchFile.toString());
        return args;
    }

    private List<Integer> buildStripCandidates(Path repoRoot, int configuredStrip) {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        candidates.add(configuredStrip);

        Path workspaceRelativeRepoRoot = workspaceRoot.relativize(repoRoot);
        int workspaceDepth = Math.max(0, workspaceRelativeRepoRoot.getNameCount());
        if (workspaceDepth > 0) {
            candidates.add(Math.min(8, configuredStrip + workspaceDepth));
        }

        if (configuredStrip + 1 <= 8) {
            candidates.add(configuredStrip + 1);
        }
        if (configuredStrip + 2 <= 8) {
            candidates.add(configuredStrip + 2);
        }
        if (configuredStrip > 0) {
            candidates.add(configuredStrip - 1);
        }

        return List.copyOf(candidates);
    }

    private boolean isLikelyStripMismatch(String errorDetail) {
        if (errorDetail == null || errorDetail.isBlank()) {
            return false;
        }

        String normalized = errorDetail.toLowerCase(Locale.ROOT);
        return normalized.contains("does not exist in index")
                || normalized.contains("no such file or directory")
                || normalized.contains("outside repository");
    }

    private String buildCommitChangesResultJson(JsonNode payload) throws IOException, InterruptedException {
        Path repoRoot = resolveRepoRoot(payload);
        String message = firstNonBlank(payload.path("message").asText(null));
        if (message.isBlank()) {
            throw new IllegalArgumentException("message is required for commit_changes");
        }

        boolean allowEmpty = parseBoolean(payload.get("allowEmpty"), false);
        boolean stageAll = parseBoolean(payload.get("all"), true);

        List<String> selectedPaths = extractPathSpecs(payload, repoRoot);
        if (!selectedPaths.isEmpty()) {
            ArrayList<String> addArgs = new ArrayList<>(List.of("add", "-A", "--"));
            addArgs.addAll(selectedPaths);
            runGitChecked(repoRoot, addArgs, "failed to stage selected paths");
        } else if (stageAll) {
            runGitChecked(repoRoot, List.of("add", "-A"), "failed to stage workspace changes");
        }

        List<String> stagedFiles = parseLines(
                runGitChecked(repoRoot, List.of("diff", "--cached", "--name-only"),
                        "failed to inspect staged files").output());
        if (stagedFiles.isEmpty() && !allowEmpty) {
            throw new IllegalStateException("no staged changes to commit");
        }

        ArrayList<String> commitArgs = new ArrayList<>(List.of("commit", "-m", message));
        if (allowEmpty) {
            commitArgs.add("--allow-empty");
        }

        CommandResult commitResult = runGitChecked(repoRoot, commitArgs, "failed to create commit");
        String commitHash = runGitChecked(repoRoot, List.of("rev-parse", "HEAD"), "failed to resolve commit hash")
                .output();

        ObjectNode result = baseResultNode();
        result.put("repoPath", relativePathString(repoRoot));
        result.put("branch", currentBranch(repoRoot));
        result.put("commitHash", commitHash);
        result.put("message", message);
        result.put("stagedFileCount", stagedFiles.size());
        result.put("summary", abbreviate(firstNonBlank(commitResult.output(), message), 600));

        ArrayNode stagedArray = result.putArray("stagedFiles");
        for (String stagedFile : stagedFiles) {
            stagedArray.add(stagedFile);
        }

        return objectMapper.writeValueAsString(result);
    }

    private ObjectNode baseResultNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", "v1");
        node.put("worker", WORKER_NAME);
        return node;
    }

    private Path resolveRepoRoot(JsonNode payload) throws IOException, InterruptedException {
        String rawRepoPath = firstNonBlank(payload.path("repoPath").asText(null));
        if (!rawRepoPath.isBlank()) {
            Path repoCandidate = resolveWorkspacePath(rawRepoPath);
            Path start = Files.isDirectory(repoCandidate) ? repoCandidate : repoCandidate.getParent();
            if (start == null) {
                throw new IllegalArgumentException("repoPath must point to a directory inside workspace");
            }
            return discoverRepoRoot(start);
        }

        CommandResult workspaceGitProbe = runGit(workspaceRoot, List.of("rev-parse", "--show-toplevel"));
        if (workspaceGitProbe.exitCode() == 0 && !workspaceGitProbe.output().isBlank()) {
            return ensureRepoWithinWorkspace(Path.of(workspaceGitProbe.output().trim()).normalize());
        }

        List<Path> discoveredRepositories = discoverRepositories();
        if (discoveredRepositories.isEmpty()) {
            throw new IllegalArgumentException("no git repository found in workspace; provide repoPath");
        }
        if (discoveredRepositories.size() > 1) {
            String options = discoveredRepositories.stream()
                    .map(this::relativePathString)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("multiple git repositories found in workspace; provide repoPath. candidates: "
                    + options);
        }
        return discoveredRepositories.get(0);
    }

    private List<Path> discoverRepositories() throws IOException {
        LinkedHashSet<Path> repositories = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(workspaceRoot, 4)) {
            stream.filter(path -> ".git".equals(path.getFileName() == null ? "" : path.getFileName().toString()))
                    .map(Path::getParent)
                    .filter(path -> path != null && path.startsWith(workspaceRoot))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(repositories::add);
        }
        return List.copyOf(repositories);
    }

    private Path discoverRepoRoot(Path start) throws IOException, InterruptedException {
        Path normalizedStart = start.toAbsolutePath().normalize();
        if (!normalizedStart.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("repoPath must stay within workspace root: " + start);
        }

        CommandResult result = runGit(normalizedStart, List.of("rev-parse", "--show-toplevel"));
        if (result.exitCode() != 0 || result.output().isBlank()) {
            String reason = result.output().isBlank() ? "not a git repository" : result.output();
            throw new IllegalArgumentException("path is not in a git repository: " + reason);
        }
        return ensureRepoWithinWorkspace(Path.of(result.output()).normalize());
    }

    private Path ensureRepoWithinWorkspace(Path repoRoot) {
        Path normalized = repoRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized)) {
                normalized = normalized.toRealPath().normalize();
            }
        } catch (IOException ignored) {
            // Fallback to normalized absolute path when real path resolution is unavailable.
        }
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("repository root must stay within workspace root: " + repoRoot);
        }
        return normalized;
    }

    private Path normalizeWorkspaceRoot(Path configuredWorkspaceRoot) {
        Path fallback = configuredWorkspaceRoot == null
                ? Path.of("").toAbsolutePath().normalize()
                : configuredWorkspaceRoot.toAbsolutePath().normalize();

        try {
            if (Files.exists(fallback)) {
                return fallback.toRealPath().normalize();
            }
        } catch (IOException ignored) {
            // Keep fallback when symbolic link normalization is not available.
        }
        return fallback;
    }

    private List<String> extractPathSpecs(JsonNode payload, Path repoRoot) {
        LinkedHashSet<String> specs = new LinkedHashSet<>();
        appendPathSpec(specs, repoRoot, payload.path("path"));
        appendPathSpec(specs, repoRoot, payload.path("file"));

        JsonNode paths = payload.path("paths");
        if (paths.isArray()) {
            for (JsonNode item : paths) {
                appendPathSpec(specs, repoRoot, item);
            }
        }

        return List.copyOf(specs);
    }

    private void appendPathSpec(Set<String> target, Path repoRoot, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual()) {
            return;
        }

        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return;
        }

        Path resolved = resolvePathInsideRoot(raw, repoRoot, "path must stay within repository root: ");
        String relative = repoRoot.relativize(resolved).toString().replace('\\', '/');
        if (relative.isBlank()) {
            target.add(".");
            return;
        }
        target.add(relative);
    }

    private List<String> extractChangedFilesFromPatch(String patch) {
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        String[] lines = patch.split("\\R");
        for (String line : lines) {
            if (!line.startsWith("+++ ")) {
                continue;
            }

            String rawPath = line.substring(4).trim();
            if (rawPath.isBlank() || "/dev/null".equals(rawPath)) {
                continue;
            }
            if (rawPath.startsWith("a/") || rawPath.startsWith("b/")) {
                rawPath = rawPath.substring(2);
            }
            if (!rawPath.isBlank()) {
                changed.add(rawPath.replace('\\', '/'));
            }
        }
        return List.copyOf(changed);
    }

    private String currentBranch(Path repoRoot) throws IOException, InterruptedException {
        return runGitChecked(repoRoot, List.of("rev-parse", "--abbrev-ref", "HEAD"),
                "failed to resolve current branch").output();
    }

    private CommandResult runGitChecked(Path repoRoot, List<String> args, String failureMessage)
            throws IOException, InterruptedException {
        CommandResult result = runGit(repoRoot, args);
        if (result.exitCode() != 0) {
            String detail = firstNonBlank(result.output(), "exitCode=" + result.exitCode());
            throw new IllegalStateException(failureMessage + ": " + detail);
        }
        return result;
    }

    private CommandResult runGit(Path workingDirectory, List<String> args) throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workingDirectory.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }

        byte[] outputBytes = process.getInputStream().readAllBytes();
        String output = readCommandOutput(outputBytes);
        return new CommandResult(process.exitValue(), output);
    }

    private String readCommandOutput(byte[] outputBytes) {
        if (outputBytes == null || outputBytes.length == 0) {
            return "";
        }

        boolean truncated = outputBytes.length > maxCommandOutputBytes;
        byte[] effectiveBytes = outputBytes;
        if (truncated) {
            effectiveBytes = Arrays.copyOf(outputBytes, maxCommandOutputBytes);
        }

        String output = new String(effectiveBytes, StandardCharsets.UTF_8).trim();
        if (truncated) {
            return output + "\n...<truncated>";
        }
        return output;
    }

    private Path resolveWorkspacePath(String rawPath) {
        return resolvePathInsideRoot(rawPath, workspaceRoot, "path must stay within workspace root: ");
    }

    private Path resolvePathInsideRoot(String rawPath, Path root, String prefix) {
        try {
            Path candidate = Paths.get(rawPath.trim());
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : root.resolve(candidate).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException(prefix + rawPath);
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("invalid path: " + rawPath, exception);
        }
    }

    private String relativePathString(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private PayloadParseResult parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return PayloadParseResult.success(objectMapper.createObjectNode());
        }

        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                return PayloadParseResult.failure("payload JSON parse failed: payload must be a JSON object");
            }
            return PayloadParseResult.success(node);
        } catch (IOException exception) {
            return PayloadParseResult.failure("payload JSON parse failed: " + exception.getMessage());
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static List<String> parseLines(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .toList();
    }

    private static boolean parseBoolean(JsonNode node, boolean defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String normalized = node.asText().trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        return defaultValue;
    }

    private static int parseInt(JsonNode node, int defaultValue, int min, int max) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }

        Integer parsed;
        if (node.isInt() || node.isLong()) {
            parsed = node.asInt();
        } else if (node.isTextual()) {
            try {
                parsed = Integer.parseInt(node.textValue().trim());
            } catch (NumberFormatException exception) {
                parsed = null;
            }
        } else {
            parsed = null;
        }

        if (parsed == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(1, maxLength)) + "...";
    }

    private record PayloadParseResult(boolean parseable, JsonNode payloadNode, String errorMessage) {

        static PayloadParseResult success(JsonNode payloadNode) {
            return new PayloadParseResult(true, payloadNode, null);
        }

        static PayloadParseResult failure(String errorMessage) {
            return new PayloadParseResult(false, null, errorMessage);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}

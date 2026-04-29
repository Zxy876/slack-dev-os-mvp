package com.asyncaiflow.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.asyncaiflow.support.ApiException;
import com.asyncaiflow.web.Result;
import com.asyncaiflow.web.dto.FileUploadResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping({"/api/files", "/api/v1/files"})
@Tag(name = "File Upload", description = "Minimal upload API for raw 3D scan files")
public class FileUploadController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileUploadController.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "obj", "ply", "zip", "glb",
            "jpg", "jpeg", "png", "heic", "webp");
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String UPLOAD_URL_PREFIX = "/files/upload/";

    private final Path uploadDir;

    public FileUploadController(@Value("${asyncaiflow.upload-dir:/tmp/asyncaiflow_uploads}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a raw scan file", description = "Accepts .obj/.ply/.glb files directly, or a .zip containing .obj/.mtl/textures")
    public Result<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "upload file must not be empty");
        }

        String incomingFileName = file.getOriginalFilename();
        String originalFileName;
        if (incomingFileName == null || incomingFileName.isBlank()) {
            originalFileName = "scan";
        } else {
            originalFileName = incomingFileName.trim();
        }
        String extension = resolveExtension(originalFileName);
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(normalizedExtension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "only .obj, .ply, .zip and .glb files are supported");
        }
        if ("obj".equals(normalizedExtension)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "OBJ upload requires a .zip asset bundle with .obj/.mtl/texture files"
            );
        }

        try {
            Files.createDirectories(uploadDir);
            if ("zip".equals(normalizedExtension)) {
                return Result.ok("zip uploaded", handleZipUpload(file, originalFileName));
            }

            String saveName = FILE_TS_FORMAT.format(LocalDateTime.now())
                    + "_"
                    + UUID.randomUUID().toString().replace("-", "")
                    + "."
                    + normalizedExtension;
            Path target = resolveWithinUploadDir(uploadDir.resolve(saveName));
            Files.createDirectories(target.getParent());

            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String fileUrl = UPLOAD_URL_PREFIX + saveName;
            return Result.ok("file uploaded", new FileUploadResponse(
                    originalFileName,
                    fileUrl,
                    target.getParent().toString(),
                    target.toString(),
                    file.getSize()
            ));
        } catch (IOException exception) {
            LOGGER.error("Failed to save upload file to {}", uploadDir, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "failed to save upload file");
        }
    }

    private String resolveExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1);
    }

    private FileUploadResponse handleZipUpload(MultipartFile file, String originalFileName) throws IOException {
        String folderName = UUID.randomUUID().toString();
        String zipBaseName = stripExtension(originalFileName);
        Path extractionDir = resolveWithinUploadDir(uploadDir.resolve(folderName));
        Files.createDirectories(extractionDir);

        try (ZipInputStream zipInput = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                Path entryTarget = resolveZipEntryTarget(extractionDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryTarget);
                } else {
                    Files.createDirectories(entryTarget.getParent());
                    Files.copy(zipInput, entryTarget, StandardCopyOption.REPLACE_EXISTING);
                }
                zipInput.closeEntry();
            }
        }

        Path objFile = findPrimaryObj(extractionDir, zipBaseName);
        validateObjAssetBundle(extractionDir, objFile);
        Path relativePath = extractionDir.relativize(objFile);
        String fileUrl = UPLOAD_URL_PREFIX + folderName + "/" + relativePath.toString().replace('\\', '/');

        return new FileUploadResponse(
                originalFileName,
                fileUrl,
                extractionDir.toString(),
                objFile.toString(),
                file.getSize()
        );
    }

    private Path findPrimaryObj(Path extractionDir, String zipBaseName) throws IOException {
        List<Path> objFiles;
        try (Stream<Path> paths = Files.walk(extractionDir)) {
            objFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> "obj".equals(resolveExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (objFiles.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "zip file does not contain any .obj model"
            );
        }

        if (objFiles.size() == 1) {
            return objFiles.get(0);
        }

        String normalizedBase = zipBaseName.toLowerCase(Locale.ROOT);
        Path rootMatch = objFiles.stream()
                .filter(path -> path.getParent() != null && path.getParent().equals(extractionDir))
                .filter(path -> stripExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT).equals(normalizedBase))
                .findFirst()
                .orElse(null);
        if (rootMatch != null) {
            return rootMatch;
        }

        Path nameMatch = objFiles.stream()
                .filter(path -> stripExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT).equals(normalizedBase))
                .findFirst()
                .orElse(null);
        if (nameMatch != null) {
            return nameMatch;
        }

        return objFiles.stream()
                .sorted(Comparator
                        .comparingLong((Path path) -> {
                            try {
                                return Files.size(path);
                            } catch (IOException exception) {
                                return 0L;
                            }
                        })
                        .reversed()
                        .thenComparing(Path::toString))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ARGUMENT",
                        "zip file does not contain a valid primary .obj model"
                ));
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index <= 0) {
            return fileName;
        }
        return fileName.substring(0, index);
    }

    private void validateObjAssetBundle(Path extractionDir, Path objFile) throws IOException {
        List<String> mtllibRefs = readObjMtllibRefs(objFile);
        if (mtllibRefs.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "zip asset bundle must include OBJ material references (mtllib)"
            );
        }

        List<Path> materialFiles = new ArrayList<>();
        for (String mtllibRef : mtllibRefs) {
            Path materialPath = resolveZipEntryTarget(extractionDir, objFile.getParent().resolve(mtllibRef).toString());
            if (!Files.isRegularFile(materialPath)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ARGUMENT",
                        "zip asset bundle is missing referenced material file: " + mtllibRef
                );
            }
            materialFiles.add(materialPath);
        }

        List<String> textureRefs = new ArrayList<>();
        for (Path materialFile : materialFiles) {
            textureRefs.addAll(readMtlTextureRefs(materialFile));
        }

        if (textureRefs.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "zip asset bundle must include texture references in .mtl (png/jpg/jpeg/webp)"
            );
        }

        for (String textureRef : textureRefs) {
            Path texturePath = resolveZipEntryTarget(extractionDir, objFile.getParent().resolve(textureRef).toString());
            if (!Files.isRegularFile(texturePath)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ARGUMENT",
                        "zip asset bundle is missing referenced texture file: " + textureRef
                );
            }
        }
    }

    private List<String> readObjMtllibRefs(Path objFile) throws IOException {
        List<String> refs = new ArrayList<>();
        for (String line : Files.readAllLines(objFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("mtllib ")) {
                String ref = trimmed.substring("mtllib ".length()).trim();
                if (!ref.isEmpty()) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    private List<String> readMtlTextureRefs(Path mtlFile) throws IOException {
        List<String> refs = new ArrayList<>();
        for (String line : Files.readAllLines(mtlFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("map_kd ")
                    || lower.startsWith("map_ka ")
                    || lower.startsWith("map_ks ")
                    || lower.startsWith("map_ke ")
                    || lower.startsWith("map_bump ")
                    || lower.startsWith("bump ")
                    || lower.startsWith("map_d "))) {
                continue;
            }

            int separator = trimmed.indexOf(' ');
            if (separator <= 0 || separator >= trimmed.length() - 1) {
                continue;
            }
            String remainder = trimmed.substring(separator + 1).trim();
            if (remainder.isEmpty()) {
                continue;
            }

            String[] tokens = remainder.split("\\s+");
            String candidate = tokens[tokens.length - 1];
            String ext = resolveExtension(candidate).toLowerCase(Locale.ROOT);
            if (Set.of("png", "jpg", "jpeg", "webp").contains(ext)) {
                refs.add(candidate);
            }
        }
        return refs;
    }

    private Path resolveZipEntryTarget(Path extractionDir, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "zip entry name must not be empty");
        }
        Path resolved = extractionDir.resolve(entryName).toAbsolutePath().normalize();
        if (!resolved.startsWith(extractionDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "zip entry path is invalid");
        }
        return resolved;
    }

    private Path resolveWithinUploadDir(Path candidate) {
        Path resolved = candidate.toAbsolutePath().normalize();
        if (!resolved.startsWith(uploadDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "invalid upload path");
        }
        return resolved;
    }
}

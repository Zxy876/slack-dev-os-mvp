package com.asyncaiflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Uploaded file metadata")
public record FileUploadResponse(
        @Schema(description = "Original file name", example = "old-jacket-scan.obj")
        String originalFileName,
        @Schema(description = "Saved file reference used as rawScanUrl; direct uploads return an absolute path, ZIP uploads return a /files/upload/... URL", example = "/files/upload/550e8400-e29b-41d4-a716-446655440000/model/textured_scan.obj")
        String rawScanUrl,
        @Schema(description = "Directory containing uploaded source assets (for zip uploads this is the extraction root)", example = "/tmp/asyncaiflow_uploads/550e8400-e29b-41d4-a716-446655440000")
        String uploadAssetDir,
        @Schema(description = "Primary source model entry path selected from uploaded assets", example = "/tmp/asyncaiflow_uploads/550e8400-e29b-41d4-a716-446655440000/model/textured_scan.obj")
        String entryModelPath,
        @Schema(description = "Saved file size in bytes", example = "16384")
        long size
) {
}

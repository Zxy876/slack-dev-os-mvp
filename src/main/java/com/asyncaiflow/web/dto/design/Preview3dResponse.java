package com.asyncaiflow.web.dto.design;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "3D preview assets for the generated design")
public record Preview3dResponse(
        @Schema(description = "3D model asset URL", example = "https://cdn.example.com/model/task_01.glb", nullable = true)
        String modelUrl,
        @Schema(description = "Thumbnail URL", example = "https://cdn.example.com/model/task_01.png", nullable = true)
        String thumbnailUrl
) {
}
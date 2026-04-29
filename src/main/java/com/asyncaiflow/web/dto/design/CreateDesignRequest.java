package com.asyncaiflow.web.dto.design;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for creating a design task")
public record CreateDesignRequest(
        @Schema(description = "Input mode", example = "TEXT", allowableValues = {"TEXT", "IMAGE", "TEXT_AND_IMAGE"})
        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "TEXT|IMAGE|TEXT_AND_IMAGE", message = "must be one of TEXT, IMAGE, TEXT_AND_IMAGE")
        String inputType,
        @Schema(description = "Natural language description of the design", example = "一件春季女式短款风衣，轻薄防水，浅卡其色")
        @Size(max = 4000, message = "must not exceed 4000 characters")
        String prompt,
        @Schema(description = "Reference image URL", example = "https://example.com/reference/front-view.png", nullable = true)
        @Size(max = 512, message = "must not exceed 512 characters")
        String designImageUrl,
        @Schema(description = "Raw scan model URL or absolute path for pre-cleaning", example = "https://example.com/raw/old-coat.obj", nullable = true)
        @Size(max = 2048, message = "must not exceed 2048 characters")
        String rawScanUrl,
        @Schema(description = "Optional rendering or business preferences")
        Map<String, Object> options
) {
}
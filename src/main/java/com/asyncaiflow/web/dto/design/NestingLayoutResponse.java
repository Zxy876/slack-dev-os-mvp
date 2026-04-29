package com.asyncaiflow.web.dto.design;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "2-D fabric nesting layout produced by the DP worker")
public record NestingLayoutResponse(
        @Schema(description = "Fabric roll width in mm", example = "1500.0")
        Double fabricWidthMm,
        @Schema(description = "Consumed fabric length in mm", example = "1530.0")
        Double consumedLengthMm,
        @Schema(description = "Fabric utilization ratio between 0 and 1", example = "0.739")
        Double utilization,
        @Schema(description = "Individual piece placements on the fabric")
        List<PlacementResponse> placements
) {
}

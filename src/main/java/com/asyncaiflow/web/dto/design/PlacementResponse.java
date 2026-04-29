package com.asyncaiflow.web.dto.design;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single garment piece placement on the fabric roll")
public record PlacementResponse(
        @Schema(description = "Component identifier matching the DSL component id", example = "body_front_left")
        String componentId,
        @Schema(description = "X origin on fabric in mm", example = "10.0")
        Double xMm,
        @Schema(description = "Y origin on fabric in mm", example = "0.0")
        Double yMm,
        @Schema(description = "Placed width in mm (after optional rotation)", example = "380.0")
        Double widthMm,
        @Schema(description = "Placed height in mm (after optional rotation)", example = "620.0")
        Double heightMm,
        @Schema(description = "Whether the piece was rotated 90° to fit", example = "false")
        Boolean rotated
) {
}

package org.eclipse.edc.dse.telemetry.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@OpenAPIDefinition(info = @Info(description = "This API is used to generate billing reports",
        title = "Telemetry CSV Manager API", version = "1"),
        security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
@Tag(name = "Telemetry CSV Manager API")
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
@SecurityScheme(
        name = "apiKeyAuth",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "x-api-key"
)
public interface TelemetryCsvManagerApi {

    @Operation(description = "Retrieves Reports",
            operationId = "getReport",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The telemetry event was processed successfully", content = @Content(schema = @Schema(implementation = String.class), mediaType = "text/csv")),
                    @ApiResponse(responseCode = "400", description = "Invalid date range provided", content = @Content(schema = @Schema(implementation = String.class), mediaType = "application/json")),
                    @ApiResponse(responseCode = "401", description = "Invalid JWT token", content = @Content(schema = @Schema(implementation = String.class), mediaType = "application/json")),
                    @ApiResponse(responseCode = "403", description = "Missing/invalid participant in roles, unexpected number of participant roles or participant does not exist",
                            content = @Content(schema = @Schema(implementation = String.class), mediaType = "application/json")),
                    @ApiResponse(responseCode = "404", description = "Report not found",
                            content = @Content(schema = @Schema(implementation = String.class), mediaType = "application/json")),
                    @ApiResponse(responseCode = "500", description = "Internal server error",
                            content = @Content(schema = @Schema(implementation = String.class), mediaType = "application/json"))

            }
    )
    Response getReport(@Parameter(hidden = true) @HeaderParam("Authorization") String authHeader, @Parameter(description = "Target month") @QueryParam("month") Integer month,
                       @Parameter(description = "Target year") @QueryParam("year") Integer year);
}
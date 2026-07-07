package org.eclipse.edc.dse.telemetry.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.eclipse.edc.spi.iam.TokenRepresentation;

@OpenAPIDefinition(
        info = @Info(description = "Telemetry Service Credential APIs.", title = "Credential API", version = "v1alpha"),
        security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
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
public interface TelemetryServiceCredentialApi {

    @Operation(description = "Return a SAS token for publishing telemetry records.",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            responses = {
                    @ApiResponse(responseCode = "200", description = "Token was generated successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed"),
                    @ApiResponse(responseCode = "500", description = "Server failed to generate token")
            }
    )
    TokenRepresentation generateToken(String token);

}

package org.eclipse.edc.dse.controlplane.catalog.filter;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.core.Response;

@OpenAPIDefinition(security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
@Tag(name = "Federated Catalog Filter Endpoint for Participant Control-Plane",
        description = "A service that allows participants to call the filtering of the federated catalog")
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
public interface FederatedCatalogFilteringApiV2 {

    @Operation(description = "Creates a Token Representation from Identity Hub and sends it to the filtering of the federated catalog for asset filtering",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            responses = {
                    @ApiResponse(responseCode = "500", description = "Failed to fetch the filtered catalog")
            }
    )
    Response getCatalog();

}
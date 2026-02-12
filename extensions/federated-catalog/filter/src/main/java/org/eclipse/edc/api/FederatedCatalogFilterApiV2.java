/*
 *  Copyright (c) 2022 Amadeus
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Amadeus - Initial implementation
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - handle HEAD requests
 *
 */

package org.eclipse.edc.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.FilterRequest;


@OpenAPIDefinition(security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
@Tag(name = "Federated Catalog Filter",
        description = "A service that allows the filtering of the assets in the federated catalog based on participant vcs")
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
public interface FederatedCatalogFilterApiV2 {


    @Operation(description = "Filters federated catalog assets based on participant vcs",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            responses = {
                    @ApiResponse(responseCode = "204", description = "No catalog entries after filtering"),
                    @ApiResponse(responseCode = "400", description = "Missing access token"),
                    @ApiResponse(responseCode = "403", description = "Access token is expired or invalid"),
                    @ApiResponse(responseCode = "500", description = "Failed to transfer data, something went wrong on the server side")
            }
    )
    Response filter(FilterRequest req);

}

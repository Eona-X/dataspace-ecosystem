package org.eclipse.edc.issuerservice.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.eclipse.dse.spi.issuerservice.DomainAttestation;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.web.spi.ApiErrorDetail;

import java.util.Collection;

@OpenAPIDefinition(info = @Info(description = "This API is used to manipulate domain attestations",
        title = "Issuer Service Domain Attestation API",
        version = "1"), security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
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
@Tag(name = "Domain Attestation Admin API")
public interface DomainAttestationAdminApi {


    @Operation(description = "Adds a new domain attestation.",
            operationId = "createDomainAttestation",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = DomainAttestation.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "204", description = "The domain attestation was added successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "409", description = "Can't add the domain attestation, because a object with the same ID already exists",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void createDomainAttestation(String participantContextId, DomainAttestationDto domainAttestationDto);

    @Operation(description = "Updates domain attestation.",
            operationId = "updateDomainAttestation",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = DomainAttestation.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "204", description = "The domain attestation was updated successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "401", description = "The request could not be completed, because either the authentication was missing or was not valid.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "404", description = "Can't update the domain attestation because it was not found.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void updateDomainAttestation(String participantContextId, DomainAttestationDto domainAttestationDto);

    @Operation(description = "Delete domain attestation.",
            operationId = "deleteDomainAttestation",
            responses = {
                    @ApiResponse(responseCode = "204", description = "The domain attestation was deleted successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "404", description = "Can't delete domain attestation because it was not found.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void deleteDomainAttestation(String participantContextId, String id);

    @Operation(description = "Gets all domain attestations for a certain query.",
            operationId = "queryDomainAttestations",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = QuerySpec.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "200", description = "A list of domain attestations metadata.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DomainAttestation.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    Collection<DomainAttestation> queryDomainAttestations(String participantContextId, QuerySpec querySpec);
}


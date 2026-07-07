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
import org.eclipse.dse.spi.issuerservice.MembershipAttestation;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.web.spi.ApiErrorDetail;

import java.util.Collection;

@OpenAPIDefinition(info = @Info(description = "This API is used to manipulate membership attestations", title = "Issuer Service Membership Attestation API", version = "1"),
        security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")})
@Tag(name = "Membership Attestation Admin API")
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
public interface MembershipAttestationAdminApi {


    @Operation(description = "Adds a new membership attestation.",
            operationId = "createMembershipAttestation",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = MembershipAttestationDto.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "201", description = "The membership attestation was added successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "409", description = "Can't add the membership attestation, because a object with the same ID already exists",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void createMembershipAttestation(String participantContextId, MembershipAttestationDto membershipAttestation);

    @Operation(description = "Updates membership attestation.",
            operationId = "updateMembershipAttestation",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = MembershipAttestationDto.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "204", description = "The membership attestation was updated successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "401", description = "The request could not be completed, because either the authentication was missing or was not valid.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "404", description = "Can't update the membership attestation because it was not found.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void updateMembershipAttestation(String participantContextId, MembershipAttestationDto membershipAttestation);

    @Operation(description = "Delete membership attestation.",
            operationId = "deleteMembershipAttestation",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            responses = {
                    @ApiResponse(responseCode = "204", description = "The membership attestation was deleted successfully."),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "404", description = "Can't delete membership attestation because it was not found.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    void deleteMembershipAttestation(String participantContextId, String id);

    @Operation(description = "Gets all membership attestations for a certain query.",
            operationId = "queryMembershipAttestations",
            security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKeyAuth")},
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = QuerySpec.class), mediaType = "application/json")),
            responses = {
                    @ApiResponse(responseCode = "200", description = "A list of membership attestations metadata.",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = MembershipAttestation.class)), mediaType = "application/json")),
                    @ApiResponse(responseCode = "400", description = "Request body was malformed, or the request could not be processed",
                            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiErrorDetail.class)), mediaType = "application/json"))
            }
    )
    Collection<MembershipAttestation> queryMembershipAttestations(String participantContextId, QuerySpec querySpec);
}


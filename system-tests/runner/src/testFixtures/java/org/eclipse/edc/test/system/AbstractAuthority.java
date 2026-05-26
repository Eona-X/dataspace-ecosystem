package org.eclipse.edc.test.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.apache.http.HttpStatus;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialFormat;
import org.eclipse.edc.issuerservice.api.DomainAttestationDto;
import org.eclipse.edc.issuerservice.api.MembershipAttestationDto;
import org.eclipse.edc.issuerservice.api.admin.credentialdefinition.v1.unstable.model.CredentialDefinitionDto;
import org.eclipse.edc.issuerservice.api.admin.credentials.v1.unstable.model.AttestationDefinitionRequest;
import org.eclipse.edc.issuerservice.api.admin.holder.v1.unstable.model.HolderDto;
import org.eclipse.edc.issuerservice.spi.issuance.model.MappingDefinition;
import org.eclipse.edc.jsonld.spi.JsonLd;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.eclipse.dse.iam.policy.PolicyConstants.DOMAIN_CREDENTIAL_TYPE;
import static org.eclipse.edc.test.system.ParticipantConstants.CLUSTER_HOSTNAME;

abstract class AbstractAuthority extends AbstractEntity {

    public static final String DOMAIN_ROUTE = "route";
    public static final String DOMAIN_TRAVEL = "travel";

    @Override
    protected String did() {
        return "did:web:" + name() + "-identityhub%3A8383:api:did";
    }

    @Override
    protected String vaultUrl() {
        return "http://%s/%s/vault".formatted(CLUSTER_HOSTNAME, name());
    }

    protected String telemetryUrl() {
        return "http://%s/%s/telemetry-events".formatted(CLUSTER_HOSTNAME, name());
    }

    protected String csvManagerUrl() {
        return "http://%s/%s/billing-reports".formatted(CLUSTER_HOSTNAME, name());
    }

    @Override
    protected String identityHubIdentityUrl() {
        return "http://%s/%s/ih/identity".formatted(CLUSTER_HOSTNAME, name());
    }

    protected String catalogUrl() {
        return "http://%s/%s/catalog".formatted(CLUSTER_HOSTNAME, name());
    }

    protected String issuerServiceAdminUrl() {
        return "http://%s/%s/is/issueradmin".formatted(CLUSTER_HOSTNAME, name());
    }

    public void defineMembershipCredential() {
        var attestationId = createMembershipCredentialAttestation();
        createMembershipCredentialDefinition(attestationId);
    }

    public void defineDomainCredential() {
        var attestationId = createDomainCredentialAttestation();
        createDomainCredentialDefinition(attestationId);
    }

    public void createParticipant(String name, String did) {
        createHolder(name, did);
        createParticipantMembershipAttestation(name, did);
        createParticipantDomainAttestation(did);
        createUnAuthorizedParticipantDomainAttestation(did);
    }

    public List<JsonObject> queryCatalog(ObjectMapper mapper, JsonLd jsonLd, String catalogUrl) throws JsonProcessingException {
        var catalogs = given()
                .baseUri(catalogUrl)
                .contentType(JSON)
                .when()
                .get()
                .then()
                .statusCode(200)
                .extract().body().asString();

        return mapper.readValue(catalogs, JsonArray.class).stream()
                .map(JsonValue::asJsonObject)
                .map(jsonObject -> jsonLd.expand(jsonObject).orElseThrow(f -> new RuntimeException(f.getFailureDetail())))
                .collect(Collectors.toList());
    }

    private String createMembershipCredentialAttestation() {
        var dto = new AttestationDefinitionRequest(
                "membership-attestation-def-1",
                "database",
                Map.of(
                        "dataSourceName", "membership",
                        "tableName", "membership_attestation"
                )
        );

        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/attestations".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());

        return dto.id();
    }

    private String createDomainCredentialAttestation() {
        var dto = new AttestationDefinitionRequest(
                "domain-attestation-def-1",
                "database",
                Map.of(
                        "dataSourceName", "domain",
                        "tableName", "domain_attestation"
                )
        );

        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/attestations".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());

        return dto.id();
    }

    private void createMembershipCredentialDefinition(String attestationId) {
        var dto = CredentialDefinitionDto.Builder.newInstance()
                .attestation(attestationId)
                .credentialType("MembershipCredential")
                .id("membership-credential-def-1")
                .format(CredentialFormat.VC1_0_JWT.name())
                .validity(TimeUnit.DAYS.toSeconds(365 * 10))
                .jsonSchemaUrl("https://example.com/schema/membership-credential.json")
                .mapping(new MappingDefinition("name", "credentialSubject.name", true))
                .mapping(new MappingDefinition("membership_type", "credentialSubject.membership.membershipType", true))
                .mapping(new MappingDefinition("membership_start_date", "credentialSubject.membership.since", true))
                .build();

        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/credentialdefinitions".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());

    }

    private void createDomainCredentialDefinition(String attestationId) {
        var dto = CredentialDefinitionDto.Builder.newInstance()
                .attestation(attestationId)
                .credentialType(DOMAIN_CREDENTIAL_TYPE)
                .id("domain-credential-def-1")
                .format(CredentialFormat.VC1_0_JWT.name())
                .validity(TimeUnit.DAYS.toSeconds(365 * 10))
                .jsonSchemaUrl("https://example.com/schema/domain-credential.json")
                .mapping(new MappingDefinition("domain", "credentialSubject.domain", true))
                .build();

        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/credentialdefinitions".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());

    }

    private void createHolder(String name, String did) {
        var dto = new HolderDto(did, did, name);

        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/holders".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());
    }

    private void createParticipantMembershipAttestation(String name, String did) {
        var dto = new MembershipAttestationDto(did, name, did, "FullMember");
        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/attestation-membership".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());

    }

    private void createParticipantDomainAttestation(String did) {
        var dto = new DomainAttestationDto(
                null,
                did,
                DOMAIN_ROUTE
        );
        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/attestation-domain".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());
    }

    private void createUnAuthorizedParticipantDomainAttestation(String did) {
        var dto = new DomainAttestationDto(
                null,
                did,
                DOMAIN_TRAVEL
        );
        given()
                .baseUri(issuerServiceAdminUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/attestation-domain".formatted(toBase64(did())))
                .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);
    }

}

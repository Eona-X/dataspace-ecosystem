package org.eclipse.edc.test.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialFormat;
import org.eclipse.edc.identityhub.api.verifiablecredentials.v1.unstable.model.CredentialDescriptor;
import org.eclipse.edc.identityhub.api.verifiablecredentials.v1.unstable.model.CredentialRequestDto;
import org.hamcrest.core.AnyOf;

import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

abstract class AbstractEntity {

    protected abstract String name();

    protected abstract String did();

    protected abstract String vaultUrl();

    protected abstract String identityHubIdentityUrl();

    public void requestCredential(String issuerDid, String credentialType, String credentialDefinitionId) {
        var dto = new CredentialRequestDto(issuerDid, did() + credentialType, List.of(
                new CredentialDescriptor(CredentialFormat.VC1_0_JWT.name(), credentialType, credentialDefinitionId)));
        given()
                .baseUri(identityHubIdentityUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/credentials/request".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());
    }

    public List<JsonObject> getCredentials(ObjectMapper mapper) throws JsonProcessingException {
        String credentials =  given()
                .baseUri(identityHubIdentityUrl())
                .when()
                .contentType(JSON)
                .get("/v1alpha/participants/%s/credentials".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx()).extract().body().asString();
        return mapper.readValue(credentials, JsonArray.class).stream()
                .map(JsonValue::asJsonObject)
                .toList();
    }

    protected String toBase64(String s) {
        return Base64.getUrlEncoder().encodeToString(s.getBytes());
    }

    protected AnyOf<Integer> isStatus2xx() {
        return anyOf(is(200), is(201), is(204));
    }

}

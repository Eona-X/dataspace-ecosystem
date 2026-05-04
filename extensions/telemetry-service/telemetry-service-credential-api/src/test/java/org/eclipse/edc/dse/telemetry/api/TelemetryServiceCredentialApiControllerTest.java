package org.eclipse.edc.dse.telemetry.api;

import io.restassured.specification.RequestSpecification;
import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ApiTest
class TelemetryServiceCredentialApiControllerTest extends RestControllerTestBase {

    private final TelemetryService telemetryService = mock();

    @Override
    protected Object controller() {
        return new TelemetryServiceCredentialApiController(telemetryService);
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port + "/v1alpha")
                .when();
    }

    @Nested
    class SasToken {

        @Test
        void success() {
            var token = UUID.randomUUID().toString();
            var sasToken = UUID.randomUUID().toString();

            when(telemetryService.createAccessToken(assertArg(tokenRepresentation -> assertThat(tokenRepresentation.getToken()).isEqualTo(token))))
                    .thenReturn(ServiceResult.success(TokenRepresentation.Builder.newInstance().token(sasToken).build()));

            var response = baseRequest()
                    .header(AUTHORIZATION, token)
                    .get("/sas-token")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .contentType(JSON)
                    .extract()
                    .as(TokenRepresentation.class);

            assertThat(response.getToken()).isEqualTo(sasToken);
        }

        @Test
        void serviceFails_shouldReturnInternalServerError() {
            when(telemetryService.createAccessToken(any())).thenReturn(ServiceResult.unexpected("error"));

            baseRequest()
                    .header(AUTHORIZATION, "token")
                    .get("/sas-token")
                    .then()
                    .statusCode(500);
        }

        @Test
        void missingToken_shouldReturnInvalidRequest() {
            baseRequest()
                    .get("/sas-token")
                    .then()
                    .statusCode(400);

            verifyNoInteractions(telemetryService);
        }
    }

}
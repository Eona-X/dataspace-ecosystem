package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.http.client.testfixtures.HttpTestUtils.testHttpClient;
import static org.eclipse.edc.util.io.Ports.getFreePort;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultTelemetryServiceClientTest {

    private static final String AUTHORITY_DID = "authorityDid";
    private static final String OWN_DID = "ownDid";

    private int port;
    private ClientAndServer server;
    private final TypeManager typeManager = new JacksonTypeManager();
    private final DidResolverRegistry didResolverRegistry = mock();
    private final IdentityService identityService = mock();
    private final PolicyEngine policyEngine = mock();
    private final TelemetryPolicy telemetryPolicy = mock();
    private TelemetryServiceClientImpl client;
    // Use a fixed clock to make tests predictable
    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    public void setUp() {
        port = getFreePort();
        server = ClientAndServer.startClientAndServer(port);
        
        // Mock policy and policy engine
        when(telemetryPolicy.get()).thenReturn(Policy.Builder.newInstance().build());
        when(policyEngine.evaluate(any(), any())).thenReturn(Result.success());
        
        client = new TelemetryServiceClientImpl(testHttpClient(), typeManager, didResolverRegistry, 
                                               AUTHORITY_DID, identityService, policyEngine, telemetryPolicy, OWN_DID, clock);
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static DidDocument createDidDocument(Service... services) {
        return DidDocument.Builder.newInstance()
                .service(List.of(services))
                .build();
    }

    private static TokenRepresentation createToken() {
        return TokenRepresentation.Builder.newInstance()
                .token(UUID.randomUUID().toString())
                .build();
    }

    @Nested
    class FetchCredential {

        @Test
        void success() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            var sasToken = createToken();

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withBody(typeManager.writeValueAsString(sasToken), MediaType.APPLICATION_JSON));

            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var result = client.fetchCredential();

            assertThat(result.succeeded()).isTrue();
            assertThat(result.getContent().getToken()).isEqualTo(sasToken.getToken());
        }

        @Test
        void didResolutionFails_shouldFail() {
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.failure("DID resolution failed"));

            Result<TokenRepresentation> result = client.fetchCredential();

            assertThat(result.succeeded()).isFalse();
            assertThat(result.getFailureDetail()).isEqualTo("DID resolution failed");
        }

        @Test
        void didDocumentDoesNotContains_TelemetryServiceCredential_shouldFail() {
            var service = new Service("test", "unknown", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var result = client.fetchCredential();

            assertThat(result.succeeded()).isFalse();
            assertThat(result.getFailureDetail()).contains("TelemetryServiceCredential");
        }

        @Test
        void httpCallFails_shouldReturnError() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withStatusCode(400));

            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("Server response");
        }

        @Test
        void obtainClientCredentialsFails_shouldPropagateFailure() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.failure("identity service failure"));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("identity service failure");
        }

        @Test
        void serverReturnsEmptyBody_shouldFail() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withStatusCode(200));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("Failed to parse response as TokenRepresentation");
        }

        @Test
        void audienceAndScopeAreSetOnTokenRequest() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            var sasToken = createToken();

            ArgumentCaptor<org.eclipse.edc.spi.iam.TokenParameters> captor = ArgumentCaptor.forClass(org.eclipse.edc.spi.iam.TokenParameters.class);
            when(identityService.obtainClientCredentials(captor.capture()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withBody(typeManager.writeValueAsString(sasToken), MediaType.APPLICATION_JSON));

            var result = client.fetchCredential();

            assertThat(result.succeeded()).isTrue();

            var params = captor.getValue();
            assertThat(params.getStringClaim("aud")).isEqualTo(AUTHORITY_DID);
        }

        @Test
        void serverReturnsMalformedJson_shouldFail() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withBody("not-json", MediaType.PLAIN_TEXT_UTF_8));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
        }

        @Test
        void serverReturnsNotFound_shouldFail() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withStatusCode(404));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("Server response");
        }

        @Test
        void serverReturnsInternalServerError_shouldFail() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withStatusCode(500));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("Server response");
        }

        @Test
        void correctUrlIsUsed_shouldAppendSasTokenPath() {
            var service = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var didDocument = createDidDocument(service);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            var sasToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            // Verify the exact URL path is used
            var expectedRequest = HttpRequest.request()
                    .withPath("/v1alpha/sas-token")
                    .withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withBody(typeManager.writeValueAsString(sasToken), MediaType.APPLICATION_JSON));

            var result = client.fetchCredential();

            assertThat(result.succeeded()).isTrue();
            assertThat(result.getContent().getToken()).isEqualTo(sasToken.getToken());
        }

        @Test
        void didDocumentWithMultipleServices_shouldSelectCorrectOne() {
            var wrongService = new Service("wrong", "WrongServiceType", "http://wrong.example.com");
            var correctService = new Service("test", "TelemetryServiceCredential", "http://localhost:" + port);
            var anotherWrongService = new Service("another", "AnotherType", "http://another.example.com");
            
            var didDocument = createDidDocument(wrongService, correctService, anotherWrongService);
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var accessToken = createToken();
            var sasToken = createToken();
            when(identityService.obtainClientCredentials(any()))
                    .thenReturn(Result.success(TokenRepresentation.Builder.newInstance().token(accessToken.getToken()).build()));

            var expectedRequest = HttpRequest.request().withHeader(AUTHORIZATION, accessToken.getToken());
            server.when(expectedRequest).respond(HttpResponse.response().withBody(typeManager.writeValueAsString(sasToken), MediaType.APPLICATION_JSON));

            var result = client.fetchCredential();

            assertThat(result.succeeded()).isTrue();
            assertThat(result.getContent().getToken()).isEqualTo(sasToken.getToken());
        }

        @Test
        void emptyDidDocument_shouldFail() {
            var didDocument = createDidDocument(); // No services
            when(didResolverRegistry.resolve(AUTHORITY_DID)).thenReturn(Result.success(didDocument));

            var result = client.fetchCredential();

            assertThat(result.failed()).isTrue();
            assertThat(result.getFailureDetail()).contains("TelemetryServiceCredential");
        }
    }
}
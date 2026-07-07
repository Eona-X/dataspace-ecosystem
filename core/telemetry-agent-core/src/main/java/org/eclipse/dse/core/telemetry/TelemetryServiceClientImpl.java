package org.eclipse.dse.core.telemetry;

import okhttp3.Request;
import okhttp3.Response;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryServiceClient;
import org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.http.spi.EdcHttpClient;
import org.eclipse.edc.http.spi.FallbackFactories;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.RequestContext;
import org.eclipse.edc.spi.iam.RequestScope;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.TypeManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.eclipse.dse.spi.telemetry.TelemetryServiceConstants.CREDENTIAL_URL_TYPE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.AUDIENCE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.EXPIRATION_TIME;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUED_AT;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUER;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.JWT_ID;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SCOPE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SUBJECT;



public class TelemetryServiceClientImpl implements TelemetryServiceClient {

    private static final String SAS_TOKEN_PATH = "/v1alpha/sas-token";

    /**
     * Token expiration time in seconds (1 hour).
     */
    private static final long TOKEN_EXPIRATION_SECONDS = 3600;

    private final EdcHttpClient httpClient;
    private final TypeManager typeManager;
    private final DidResolverRegistry didResolverRegistry;
    private final String ownDid;
    private final String authorityDid;
    private final IdentityService identityService;
    private final PolicyEngine policyEngine;
    private final TelemetryPolicy telemetryPolicy;
    private final Clock clock;

    TelemetryServiceClientImpl(EdcHttpClient httpClient, TypeManager typeManager,
                               DidResolverRegistry didResolverRegistry, String authorityDid, 
                               IdentityService identityService, PolicyEngine policyEngine, 
                               TelemetryPolicy telemetryPolicy,
                               String ownDid, Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.typeManager = Objects.requireNonNull(typeManager, "typeManager cannot be null");
        this.didResolverRegistry = Objects.requireNonNull(didResolverRegistry, "didResolverRegistry cannot be null");
        this.authorityDid = validateNotBlank(authorityDid, "authorityDid");
        this.identityService = Objects.requireNonNull(identityService, "identityService cannot be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine cannot be null");
        this.telemetryPolicy = Objects.requireNonNull(telemetryPolicy, "telemetryPolicy cannot be null");
        this.ownDid = ownDid;
        this.clock = clock;
    }

    @Override
    public Result<TokenRepresentation> fetchCredential() {
        return didResolverRegistry.resolve(authorityDid)
                .compose(this::credentialUrl)
                .compose(this::requestCredential);
    }

    private Result<TokenRepresentation> requestCredential(String url) {
        // Build token parameters following EDC pattern
        var tokenParametersBuilder = TokenParameters.Builder.newInstance();
        tokenParametersBuilder
                .claims(ISSUER, ownDid)
                .claims(SUBJECT, ownDid)
                .claims(JWT_ID, UUID.randomUUID().toString())
                .claims(ISSUED_AT, clock.instant().getEpochSecond())
                .claims(EXPIRATION_TIME, clock.instant().plusSeconds(TOKEN_EXPIRATION_SECONDS).getEpochSecond());

        // Evaluate policy to get dynamic scopes (following EDC pattern)
        var requestScopeBuilder = RequestScope.Builder.newInstance();
        var requestContext = RequestContext.Builder.newInstance()
                .direction(RequestContext.Direction.Egress)
                .build();
        var policyContext = new RequestTelemetryPolicyContext(requestContext, requestScopeBuilder);
        
        var policyEvaluationResult = policyEngine.evaluate(telemetryPolicy.get(), policyContext);
        if (policyEvaluationResult.failed()) {
            return Result.failure("Policy evaluation failed: " + String.join(", ", policyEvaluationResult.getFailureMessages()));
        }
        
        // Extract scopes from policy evaluation
        var scopes = requestScopeBuilder.build().getScopes();

        // Only add the scope claim if there are scopes returned from the policy engine evaluation
        // (following EDC pattern exactly)
        if (!scopes.isEmpty()) {
            tokenParametersBuilder.claims(SCOPE, String.join(" ", scopes));
        }

        // Build final token parameters and enforce the audience claim (following EDC pattern)
        var tokenParameters = tokenParametersBuilder
                .claims(AUDIENCE, authorityDid)
                .build();

        return identityService.obtainClientCredentials(tokenParameters)
                .map(tokenRepresentation -> createRequest(url + SAS_TOKEN_PATH, tokenRepresentation.getToken()))
                .compose(request -> httpClient.execute(request, List.of(FallbackFactories.retryWhenStatusIsNotIn(200, 204)), this::handleResponse));
    }

    private Result<String> credentialUrl(DidDocument document) {
        return document.getService().stream()
                .filter(s -> s.getType().equals(CREDENTIAL_URL_TYPE))
                .findFirst()
                .map(value -> Result.success(value.getServiceEndpoint()))
                .orElseGet(() -> Result.failure("Could not find service with type '%s' in DID document for authority '%s'. Available services: [%s]"
                        .formatted(CREDENTIAL_URL_TYPE, authorityDid,
                                  document.getService().stream()
                                          .map(Service::getType)
                                          .collect(Collectors.joining(", ")))));
    }

    private Request createRequest(String url, String token) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", token)
                .get()
                .build();
    }

    private Result<TokenRepresentation> handleResponse(Response response) {
        return getStringBody(response)
                .compose(it -> {
                    try {
                        return Result.success(typeManager.readValue(it, TokenRepresentation.class));
                    } catch (Exception e) {
                        return Result.failure("Failed to parse response as TokenRepresentation: " + e.getMessage());
                    }
                });
    }

    @NotNull
    private Result<String> getStringBody(Response response) {
        try (var body = response.body()) {
            if (body != null) {
                return Result.success(body.string());
            } else {
                return Result.failure("Body is null");
            }
        } catch (IOException e) {
            return Result.failure("Cannot read response body as String: " + e.getMessage());
        }
    }

    private static String validateNotBlank(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + " cannot be null or blank");
        }
        return value;
    }

}

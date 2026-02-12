package org.eclipse.edc.dse.controlplane.catalog.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.FilterRequest;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.AUDIENCE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.EXPIRATION_TIME;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUED_AT;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUER;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.JWT_ID;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SCOPE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SUBJECT;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_VC_TYPE_SCOPE_ALIAS;

@Path("/catalog")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CatalogFilteringController implements FederatedCatalogFilteringApiV2 {

    private final Monitor monitor;

    private final IdentityService identityService;

    private final String ownDid;

    private final Clock clock;

    private final String authorityDid;

    private final ObjectMapper mapper;

    private final HttpClient httpClient;

    private final AuthorityCatalogDidResolver authorityCatalogDidResolver;

    private static final String READ_ALL_CREDENTIAL_SCOPE = "%s:VerifiableCredential:read".formatted(DSE_VC_TYPE_SCOPE_ALIAS);

    public CatalogFilteringController(AuthorityCatalogDidResolver authorityCatalogDidResolver, Monitor monitor,
                                      IdentityService identityService, String ownDid, Clock clock, String authorityDid,
                                      DidResolverRegistry didResolverRegistry, ObjectMapper mapper) {
        this.monitor = monitor;
        this.identityService = identityService;
        this.ownDid = ownDid;
        this.clock = clock;
        this.authorityDid = authorityDid;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = mapper;
        this.authorityCatalogDidResolver = authorityCatalogDidResolver;
    }

    @GET
    @Path("/participantCatalog")
    @Override
    public Response getCatalog() {
        Result<String> catalogFilterUrlResult = authorityCatalogDidResolver.fetchCatalogFilterUrl();
        if (catalogFilterUrlResult.failed()) {
            throw new RuntimeException("Could not resolve authority catalog filter url: " +
                    catalogFilterUrlResult.getFailureMessages());
        }
        String catalogFilterUrl = catalogFilterUrlResult.getContent();
        String filteredCatalog = null;
        List<String> scopes = List.of(READ_ALL_CREDENTIAL_SCOPE);
        var tokenParametersBuilder = TokenParameters.Builder.newInstance();
        tokenParametersBuilder
                .claims(ISSUER, ownDid)
                .claims(SUBJECT, ownDid)
                .claims(JWT_ID, UUID.randomUUID().toString())
                .claims(ISSUED_AT, clock.instant().getEpochSecond())
                .claims(EXPIRATION_TIME, clock.instant().plusSeconds(3600).getEpochSecond())
                .claims(SCOPE, String.join(" ", scopes))
                .claims(AUDIENCE, authorityDid);

        var tokenParameters = tokenParametersBuilder.build();
        Result<TokenRepresentation> vcToken = identityService.obtainClientCredentials(tokenParameters);
        if (vcToken.succeeded()) {
            TokenRepresentation token = vcToken.getContent();
            FilterRequest filterRequest = new FilterRequest(token, ownDid);
            try {
                String jsonBody = mapper.writeValueAsString(filterRequest);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(catalogFilterUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                filteredCatalog = response.body();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (filteredCatalog != null && !filteredCatalog.isEmpty()) {
            return Response.ok(filteredCatalog).build();
        }
        return Response.status(500).build();
    }
}
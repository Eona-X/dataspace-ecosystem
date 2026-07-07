package org.eclipse.edc.telemetry.jwt;

import org.eclipse.dse.spi.telemetry.TelemetryServiceConstants;
import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialFactory;
import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialType;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.token.spi.TokenGenerationService;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.AUDIENCE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.EXPIRATION_TIME;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUED_AT;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUER;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.JWT_ID;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SUBJECT;

/**
 * {@link TelemetryServiceCredentialFactory} that issues OIDC JWTs for use by the
 * telemetry agent when authenticating to the Kafka proxy.
 *
 * <h3>Claims</h3>
 * <ul>
 *   <li>{@code iss} — the authority's DID, identifying the token issuer.</li>
 *   <li>{@code sub} — the participant's DID, identifying the token subject.</li>
 *   <li>{@code aud} — configurable audience, defaults to {@value DEFAULT_AUDIENCE}.
 *       Should match the {@code --client-id} parameter of the Kafka proxy.</li>
 *   <li>{@code kid} — the vault alias of the public key, enabling the proxy to find
 *       the correct key in the JWKS endpoint for signature verification.</li>
 *   <li>{@code jti} — random UUID per token for replay prevention.</li>
 * </ul>
 */
public class JwtTelemetryServiceCredentialFactory implements TelemetryServiceCredentialFactory {

    public static final String DEFAULT_AUDIENCE = "telemetry-agent";
    private static final String JWS_HEADER_KID = "kid";
    private static final String PARAM_TOKEN_GENERATION_SERVICE = "tokenGenerationService";
    private static final String PARAM_ISSUER = "issuer";
    private static final String PARAM_CLOCK = "clock";
    private static final String PARAM_PRIVATE_KEY_ALIAS = "privateKeyAlias";
    private static final String PARAM_PUBLIC_KEY_ALIAS = "publicKeyAlias";
    private static final String PARAM_AUDIENCE = "audience";

    private final TokenGenerationService tokenGenerationService;
    private final String issuer;
    private final long validity;
    private final Clock clock;
    private final String privateKeyAlias;
    private final String publicKeyAlias;
    private final String audience;

    public JwtTelemetryServiceCredentialFactory(TokenGenerationService tokenGenerationService, String issuer,
                                                long validity, Clock clock,
                                                String privateKeyAlias, String publicKeyAlias,
                                                String audience) {
        this.tokenGenerationService = Objects.requireNonNull(tokenGenerationService, PARAM_TOKEN_GENERATION_SERVICE);
        this.issuer = Objects.requireNonNull(issuer, PARAM_ISSUER);
        this.validity = validity;
        this.clock = Objects.requireNonNull(clock, PARAM_CLOCK);
        this.privateKeyAlias = Objects.requireNonNull(privateKeyAlias, PARAM_PRIVATE_KEY_ALIAS);
        this.publicKeyAlias = Objects.requireNonNull(publicKeyAlias, PARAM_PUBLIC_KEY_ALIAS);
        this.audience = Objects.requireNonNull(audience, PARAM_AUDIENCE);
    }

    @Override
    public Result<TokenRepresentation> create(ParticipantAgent participantAgent) {
        String participantId = participantAgent.getIdentity();

        return tokenGenerationService.generate(privateKeyAlias, builder -> builder
                        .header(JWS_HEADER_KID, publicKeyAlias)
                        .claims(ISSUER, issuer)
                        .claims(SUBJECT, participantId)
                        .claims(AUDIENCE, audience)
                        .claims(JWT_ID, UUID.randomUUID().toString())
                        .claims(ISSUED_AT, clock.instant().getEpochSecond())
                        .claims(EXPIRATION_TIME, clock.instant().plusSeconds(validity).getEpochSecond()))
                .map(tokenRepresentation -> TokenRepresentation.Builder.newInstance()
                        .token(tokenRepresentation.getToken())
                        .expiresIn(validity)
                        .additional(Map.of(TelemetryServiceConstants.CREDENTIAL_TYPE,
                                TelemetryServiceCredentialType.OIDC_TOKEN))
                        .build());
    }
}

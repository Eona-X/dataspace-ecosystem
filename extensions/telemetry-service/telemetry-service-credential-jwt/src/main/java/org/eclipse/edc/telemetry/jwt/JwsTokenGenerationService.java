package org.eclipse.edc.telemetry.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.edc.jwt.signer.spi.JwsSignerProvider;
import org.eclipse.edc.security.token.jwt.CryptoConverter;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.token.spi.TokenDecorator;
import org.eclipse.edc.token.spi.TokenGenerationService;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TokenGenerationService} implementation that produces signed JWTs using a
 * {@link JwsSignerProvider}.
 *
 * <h3>Decorator ordering</h3>
 * Decorators are applied in the order supplied by the caller, followed by an internal
 * decorator that sets the {@code alg} header to the algorithm recommended for the
 * resolved signer. This means the algorithm header is always set last and will override
 * any {@code alg} value set by a caller-supplied decorator. This is intentional: the
 * algorithm must match the actual signer, so it cannot be left to callers to specify.
 * If you need a specific algorithm, configure the key in the Vault accordingly.
 */
public class JwsTokenGenerationService implements TokenGenerationService {

    private static final String JWS_HEADER_ALG = "alg";

    private final JwsSignerProvider jwsSignerProvider;

    public JwsTokenGenerationService(JwsSignerProvider jwsSignerProvider) {
        this.jwsSignerProvider = jwsSignerProvider;
    }

    @Override
    public Result<TokenRepresentation> generate(String privateKeyId, TokenDecorator... decorators) {
        Result<JWSSigner> signerResult = jwsSignerProvider.createJwsSigner(privateKeyId);
        if (signerResult.failed()) {
            return Result.failure("JWSSigner cannot be generated for key '%s': %s"
                    .formatted(privateKeyId, signerResult.getFailureDetail()));
        }

        JWSSigner signer = signerResult.getContent();
        JWSAlgorithm algorithm = CryptoConverter.getRecommendedAlgorithm(signer);

        List<TokenDecorator> allDecorators = new ArrayList<>(List.of(decorators));
        allDecorators.add(builder -> builder.header(JWS_HEADER_ALG, algorithm.getName()));

        TokenParameters.Builder paramsBuilder = TokenParameters.Builder.newInstance();
        allDecorators.forEach(decorator -> decorator.decorate(paramsBuilder));
        TokenParameters params = paramsBuilder.build();

        try {
            JWSHeader header = JWSHeader.parse(params.getHeaders());
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder();
            params.getClaims().forEach(claimsBuilder::claim);
            SignedJWT jwt = new SignedJWT(header, claimsBuilder.build());
            jwt.sign(signer);
            return Result.success(TokenRepresentation.Builder.newInstance()
                    .token(jwt.serialize())
                    .build());
        } catch (ParseException e) {
            throw new EdcException("Error parsing JWSHeader from decorator-produced headers", e);
        } catch (JOSEException e) {
            return Result.failure("Failed to sign token with key '%s': %s".formatted(privateKeyId, e.getMessage()));
        }
    }
}

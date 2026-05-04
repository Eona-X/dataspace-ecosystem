package org.eclipse.edc.telemetry.jwt;

import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialFactory;
import org.eclipse.edc.jwt.signer.spi.JwsSignerProvider;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;

import java.time.Clock;

@Extension(value = JwtTelemetryServiceCredentialFactoryExtension.NAME)
public class JwtTelemetryServiceCredentialFactoryExtension implements ServiceExtension {

    public static final String NAME = "JWT Telemetry Credential Factory";

    @Setting(description = "Token validity in seconds", defaultValue = "3600",
            key = "dse.credential-factory.jwt.validity", required = false)
    private long validity;

    @Setting(description = "Token issuer (Authority DID)", key = "dse.authority.did")
    private String issuer;

    @Setting(description = "Private key alias in Vault for signing",
            key = "dse.telemetry.credential.signer.privatekey.alias")
    private String privateKeyAlias;

    @Setting(description = "Public key alias in Vault (used as kid header)",
            key = "dse.telemetry.credential.signer.publickey.alias")
    private String publicKeyAlias;

    @Setting(description = "JWT audience claim — must match the Kafka proxy's expected client-id",
            key = "dse.credential-factory.jwt.audience",
            defaultValue = JwtTelemetryServiceCredentialFactory.DEFAULT_AUDIENCE,
            required = false)
    private String audience;

    @Inject
    private JwsSignerProvider jwsSignerProvider;

    @Inject
    private Clock clock;

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public TelemetryServiceCredentialFactory jwtCredentialFactory() {
        JwsTokenGenerationService tokenGenerationService = new JwsTokenGenerationService(jwsSignerProvider);
        return new JwtTelemetryServiceCredentialFactory(
                tokenGenerationService, issuer, validity, clock, privateKeyAlias, publicKeyAlias, audience);
    }
}

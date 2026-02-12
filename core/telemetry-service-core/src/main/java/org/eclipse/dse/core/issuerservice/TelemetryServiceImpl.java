package org.eclipse.dse.core.issuerservice;

import org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.dse.spi.telemetry.TelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialFactory;
import org.eclipse.dse.spi.telemetry.TelemetryServiceTokenValidator;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.ServiceResult;

public class TelemetryServiceImpl implements TelemetryService {

    private final TelemetryServiceTokenValidator telemetryServiceTokenValidator;
    private final TelemetryPolicy telemetryPolicy;
    private final PolicyEngine policyEngine;
    private final TelemetryServiceCredentialFactory credentialFactory;

    public TelemetryServiceImpl(TelemetryServiceTokenValidator tokenValidator,
                                PolicyEngine policyEngine,
                                TelemetryPolicy telemetryPolicy,
                                TelemetryServiceCredentialFactory sasTokenFactory) {
        this.telemetryServiceTokenValidator = tokenValidator;
        this.telemetryPolicy = telemetryPolicy;
        this.policyEngine = policyEngine;
        this.credentialFactory = sasTokenFactory;
    }

    @Override
    public ServiceResult<TokenRepresentation> createSasToken(TokenRepresentation tokenRepresentation) {
        var participantAgentResult = telemetryServiceTokenValidator.verify(tokenRepresentation, RequestTelemetryPolicyContext::new, telemetryPolicy.get());
        if (participantAgentResult.failed()) {
            return participantAgentResult.mapFailure();
        }
        var participantAgent = participantAgentResult.getContent();
        var accessPolicyResult = policyEngine.evaluate(telemetryPolicy.get(), new TelemetryPolicyContext(participantAgent));
        if (accessPolicyResult.failed()) {
            return ServiceResult.unauthorized(accessPolicyResult.getFailureDetail());
        }
        return ServiceResult.from(credentialFactory.get());
    }

}

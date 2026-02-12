package org.eclipse.dse.core.issuerservice;


import org.eclipse.dse.spi.telemetry.TelemetryRequestMessage;
import org.eclipse.dse.spi.telemetry.TelemetryServiceTokenValidator;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentService;
import org.eclipse.edc.policy.context.request.spi.RequestPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.protocol.spi.DataspaceProfileContextRegistry;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.RequestContext;
import org.eclipse.edc.spi.iam.RequestScope;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.iam.VerificationContext;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.types.domain.message.RemoteMessage;

import static org.eclipse.edc.protocol.dsp.http.spi.types.HttpMessageProtocol.DATASPACE_PROTOCOL_HTTP;


@Extension(value = "TelemetryServiceTokenValidator.class")
public class TelemetryServiceTokenValidatorImpl implements TelemetryServiceTokenValidator {

    private final IdentityService identityService;
    private final PolicyEngine policyEngine;
    private final ParticipantAgentService agentService;
    private final DataspaceProfileContextRegistry dataspaceProfileContextRegistry;
    private final Monitor monitor;
    private final RemoteMessage remoteMessage = TelemetryRequestMessage.Builder.newInstance()
            .protocol(DATASPACE_PROTOCOL_HTTP)
            .build();

    public TelemetryServiceTokenValidatorImpl(IdentityService identityService, PolicyEngine policyEngine, Monitor monitor,
                                              ParticipantAgentService agentService, DataspaceProfileContextRegistry dataspaceProfileContextRegistry) {
        this.identityService = identityService;
        this.monitor = monitor;
        this.policyEngine = policyEngine;
        this.agentService = agentService;
        this.dataspaceProfileContextRegistry = dataspaceProfileContextRegistry;
    }

    @Override
    public ServiceResult<ParticipantAgent> verify(TokenRepresentation tokenRepresentation, RequestPolicyContext.Provider policyContextProvider, Policy policy) {
        var requestScopeBuilder = RequestScope.Builder.newInstance();
        var requestContext = RequestContext.Builder.newInstance().message(remoteMessage).direction(RequestContext.Direction.Ingress).build();
        var policyContext = policyContextProvider.instantiate(requestContext, requestScopeBuilder);
        policyEngine.evaluate(policy, policyContext);
        var verificationContext = VerificationContext.Builder.newInstance()
                .policy(policy)
                .scopes(policyContext.requestScopeBuilder().build().getScopes())
                .build();
        var tokenValidation = identityService.verifyJwtToken(tokenRepresentation, verificationContext);
        if (tokenValidation.failed()) {
            monitor.debug(() -> "Unauthorized: %s".formatted(tokenValidation.getFailureDetail()));
            return ServiceResult.unauthorized("Unauthorized");
        }

        var claimToken = tokenValidation.getContent();

        var idExtractionFunction = dataspaceProfileContextRegistry.getIdExtractionFunction(remoteMessage.getProtocol());
        if (idExtractionFunction == null) {
            return ServiceResult.badRequest("Unsupported protocol: " + remoteMessage.getProtocol());
        }

        var participantAgent = agentService.createFor(claimToken, idExtractionFunction.apply(claimToken));
        return ServiceResult.success(participantAgent);
    }
}

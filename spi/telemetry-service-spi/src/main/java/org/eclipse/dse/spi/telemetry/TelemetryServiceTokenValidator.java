package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.context.request.spi.RequestPolicyContext;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.ServiceResult;

@ExtensionPoint
public interface TelemetryServiceTokenValidator {

    /**
     * Verify the {@link TokenRepresentation}
     *
     * @param tokenRepresentation   The token
     * @param policyContextProvider The policy scope
     * @param policy               The policy to evaluate
     * @return Returns the extracted {@link ParticipantAgent} if successful, failure otherwise
     */
    ServiceResult<ParticipantAgent> verify(TokenRepresentation tokenRepresentation, RequestPolicyContext.Provider policyContextProvider, Policy policy);
}

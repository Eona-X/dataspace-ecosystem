// TelemetryServiceTokenValidatorTest.java
package org.eclipse.dse.core.issuerservice;

import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentService;
import org.eclipse.edc.policy.context.request.spi.RequestPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.protocol.spi.DataspaceProfileContextRegistry;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.RequestContext;
import org.eclipse.edc.spi.iam.RequestScope;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.spi.types.domain.message.RemoteMessage;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.eclipse.edc.junit.assertions.AbstractResultAssert.assertThat;
import static org.eclipse.edc.spi.result.ServiceFailure.Reason.UNAUTHORIZED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryServiceTokenValidatorImplTest {

    private final IdentityService identityService = mock();
    private final PolicyEngine policyEngine = mock();
    private final ParticipantAgentService agentService = mock();
    private final DataspaceProfileContextRegistry dataspaceProfileContextRegistry = mock();
    private final TelemetryServiceTokenValidatorImpl validator = new TelemetryServiceTokenValidatorImpl(
            identityService,
            policyEngine,
            mock(),
            agentService,
            dataspaceProfileContextRegistry
    );


    @Test
    void shouldVerifyToken() {
        var participantId = "participantId";
        var protocol = "dataspace-protocol-http";
        var participantAgent = new ParticipantAgent(emptyMap(), emptyMap());
        var claimToken = ClaimToken.Builder.newInstance().build();
        var policy = Policy.Builder.newInstance().build();
        var tokenRepresentation = TokenRepresentation.Builder.newInstance().build();
        when(identityService.verifyJwtToken(any(), any())).thenReturn(Result.success(claimToken));
        when(dataspaceProfileContextRegistry.getIdExtractionFunction(any())).thenReturn(ct -> participantId);
        when(agentService.createFor(any(), any())).thenReturn(participantAgent);

        var result = validator.verify(tokenRepresentation, TestRequestPolicyContext::new, policy);

        assertThat(result).isSucceeded().isSameAs(participantAgent);
        verify(agentService).createFor(claimToken, participantId);
        // The implementation creates a new policy instance, not using the original policy parameter
        verify(policyEngine).evaluate(any(Policy.class), any(RequestPolicyContext.class));
        verify(dataspaceProfileContextRegistry).getIdExtractionFunction(protocol);
        verify(identityService).verifyJwtToken(same(tokenRepresentation), any());
    }

    @Test
    void shouldReturnUnauthorized_whenTokenIsNotValid() {
        when(identityService.verifyJwtToken(any(), any())).thenReturn(Result.failure("failure"));

        var result = validator.verify(TokenRepresentation.Builder.newInstance().build(), TestRequestPolicyContext::new, Policy.Builder.newInstance().build());

        assertThat(result).isFailed().extracting(ServiceFailure::getReason).isEqualTo(UNAUTHORIZED);
    }


    private RequestPolicyContext policyContext() {
        var requestScopeBuilder = RequestScope.Builder.newInstance();
        var requestContext = RequestContext.Builder.newInstance().build();
        return new TestRequestPolicyContext(requestContext, requestScopeBuilder);
    }

    static class TestMessage implements RemoteMessage {
        @Override
        public String getProtocol() {
            return "protocol";
        }

        @Override
        public String getCounterPartyAddress() {
            return "http://connector";
        }

        @Override
        public String getCounterPartyId() {
            return null;
        }
    }

    private static class TestRequestPolicyContext extends RequestPolicyContext {

        TestRequestPolicyContext(RequestContext requestContext, RequestScope.Builder requestScopeBuilder) {
            super(requestContext, requestScopeBuilder);
        }

        @Override
        public String scope() {
            return "request.test";
        }
    }
}

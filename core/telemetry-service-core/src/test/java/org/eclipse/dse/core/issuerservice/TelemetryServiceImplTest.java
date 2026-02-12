package org.eclipse.dse.core.issuerservice;

import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.dse.spi.telemetry.TelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialFactory;
import org.eclipse.dse.spi.telemetry.TelemetryServiceTokenValidator;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceFailure;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static java.util.Collections.emptyMap;
import static org.eclipse.edc.junit.assertions.AbstractResultAssert.assertThat;
import static org.eclipse.edc.spi.result.ServiceFailure.Reason.BAD_REQUEST;
import static org.eclipse.edc.spi.result.ServiceFailure.Reason.UNAUTHORIZED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryServiceImplTest {

    private final TelemetryServiceCredentialFactory sasTokenFactory = mock();
    private final TelemetryServiceTokenValidator telemetryServiceTokenValidator = mock();
    private final PolicyEngine policyEngine = mock();
    private final TelemetryPolicy telemetryPolicy = mock();

    private final TelemetryService telemetryService = new TelemetryServiceImpl(telemetryServiceTokenValidator, policyEngine, telemetryPolicy, sasTokenFactory);


    private static TokenRepresentation createToken() {
        return TokenRepresentation.Builder.newInstance()
                .token(UUID.randomUUID().toString())
                .build();
    }

    @Nested
    class CreateSasToken {
        @Test
        void success() {
            var tokenRepresentation = createToken();
            var participantAgent = new ParticipantAgent(emptyMap(), emptyMap());
            when(telemetryServiceTokenValidator.verify(eq(tokenRepresentation), any(), any()))
                    .thenReturn(ServiceResult.success(participantAgent));

            var policy = Policy.Builder.newInstance().build();
            when(telemetryPolicy.get()).thenReturn(policy);

            when(policyEngine.evaluate(any(Policy.class), isA(TelemetryPolicyContext.class)))
                    .thenReturn(Result.success());

            // Mock the credential factory to return a token
            var sasToken = mock(TokenRepresentation.class);
            when(sasTokenFactory.get()).thenReturn(Result.success(sasToken));

            // Call the method under test
            var result = telemetryService.createSasToken(tokenRepresentation);

            // Verify the result
            assertThat(result).isSucceeded().isEqualTo(sasToken);
        }

        @Test
        void tokenValidationFails_shouldReturnUnauthorized() {
            var tokenRepresentation = createToken();
            when(telemetryServiceTokenValidator.verify(eq(tokenRepresentation), any(), any()))
                    .thenReturn(ServiceResult.badRequest("Invalid token"));

            // Call the method under test
            var result = telemetryService.createSasToken(tokenRepresentation);

            // Verify the result
            assertThat(result).isFailed().extracting(ServiceFailure::getReason).isEqualTo(BAD_REQUEST);
        }

        @Test
        void policyEvaluationFails_shouldReturnUnauthorized() {
            var tokenRepresentation = createToken();
            var participantAgent = new ParticipantAgent(emptyMap(), emptyMap());
            when(telemetryServiceTokenValidator.verify(eq(tokenRepresentation), any(), any()))
                    .thenReturn(ServiceResult.success(participantAgent));

            var policy = Policy.Builder.newInstance().build();
            when(telemetryPolicy.get()).thenReturn(policy);

            when(policyEngine.evaluate(any(Policy.class), isA(TelemetryPolicyContext.class)))
                    .thenReturn(Result.failure("Policy evaluation failed"));

            // Call the method under test
            var result = telemetryService.createSasToken(tokenRepresentation);

            // Verify the result
            assertThat(result).isFailed().extracting(ServiceFailure::getReason).isEqualTo(UNAUTHORIZED);
        }

    }

}
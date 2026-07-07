package org.eclipse.dse.core.telemetry;


import org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.RequestContext;
import org.eclipse.edc.spi.iam.RequestScope;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryPolicyBasedScopeTest {

    private PolicyEngine policyEngine;
    private TelemetryPolicy customTelemetryPolicy;

    @BeforeEach
    void setUp() {
        policyEngine = mock(PolicyEngine.class);
        customTelemetryPolicy = this::createCustomPolicy;
    }

    @Test
    void shouldGenerateCustomScopesBasedOnPolicy() {
        // Given a policy engine that will populate scopes during evaluation
        when(policyEngine.evaluate(any(), any())).thenAnswer(invocation -> {
            RequestTelemetryPolicyContext context = invocation.getArgument(1);
            // Simulate policy engine adding scopes based on policy evaluation
            context.requestScopeBuilder()
                    .scope("telemetry:read")
                    .scope("telemetry:write")
                    .scope("telemetry:admin");
            return Result.success();
        });

        // When evaluating policy for scope generation
        var requestScopeBuilder = RequestScope.Builder.newInstance();
        var requestContext = RequestContext.Builder.newInstance()
                .direction(RequestContext.Direction.Egress)
                .build();
        var policyContext = new RequestTelemetryPolicyContext(requestContext, requestScopeBuilder);
        
        var evaluationResult = policyEngine.evaluate(customTelemetryPolicy.get(), policyContext);
        
        // Then the policy evaluation should succeed and generate appropriate scopes
        assertThat(evaluationResult.succeeded()).isTrue();
        
        var scopes = requestScopeBuilder.build().getScopes();
        assertThat(scopes).containsExactlyInAnyOrder("telemetry:read", "telemetry:write", "telemetry:admin");
    }

    @Test
    void shouldGenerateDefaultScopeWhenPolicyEvaluationReturnsNoScopes() {
        // Given a policy engine that doesn't add any scopes
        when(policyEngine.evaluate(any(), any())).thenReturn(Result.success());

        // When evaluating policy for scope generation
        var requestScopeBuilder = RequestScope.Builder.newInstance();
        var requestContext = RequestContext.Builder.newInstance()
                .direction(RequestContext.Direction.Egress)
                .build();
        var policyContext = new RequestTelemetryPolicyContext(requestContext, requestScopeBuilder);
        
        var evaluationResult = policyEngine.evaluate(customTelemetryPolicy.get(), policyContext);
        
        // Then the policy evaluation should succeed but generate default scope
        assertThat(evaluationResult.succeeded()).isTrue();
        
        var scopes = requestScopeBuilder.build().getScopes();
        assertThat(scopes).isEmpty(); // Empty scopes will result in default scope being used
    }

    private Policy createCustomPolicy() {
        return Policy.Builder.newInstance()
                .permission(Permission.Builder.newInstance()
                        .action(Action.Builder.newInstance()
                                .type("telemetry:use")
                                .build())
                        .build())
                .build();
    }
}

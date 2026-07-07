package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.policy.context.request.spi.RequestPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyScope;
import org.eclipse.edc.spi.iam.RequestContext;
import org.eclipse.edc.spi.iam.RequestScope;

public class RequestTelemetryPolicyContext extends RequestPolicyContext {

    /**
     * Policy scope evaluated when an outgoing catalog request is made
     */
    @PolicyScope
    public static final String TELEMETRY_REQUEST_SCOPE = "request.telemetry";

    public RequestTelemetryPolicyContext(RequestContext requestContext, RequestScope.Builder requestScopeBuilder) {
        super(requestContext, requestScopeBuilder);
    }

    @Override
    public String scope() {
        return TELEMETRY_REQUEST_SCOPE;
    }
}

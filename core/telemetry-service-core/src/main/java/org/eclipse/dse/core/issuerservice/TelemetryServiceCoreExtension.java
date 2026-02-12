package org.eclipse.dse.core.issuerservice;

import org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.dse.spi.telemetry.TelemetryPolicyContext;
import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.dse.spi.telemetry.TelemetryServiceCredentialFactory;
import org.eclipse.dse.spi.telemetry.TelemetryServiceTokenValidator;
import org.eclipse.edc.participant.spi.ParticipantAgentService;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.protocol.spi.DataspaceProfileContextRegistry;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.dse.spi.telemetry.RequestTelemetryPolicyContext.TELEMETRY_REQUEST_SCOPE;
import static org.eclipse.dse.spi.telemetry.TelemetryPolicyContext.TELEMETRY_SCOPE;

public class TelemetryServiceCoreExtension implements ServiceExtension {

    @Inject
    private Monitor monitor;

    @Inject
    private TelemetryServiceCredentialFactory credentialFactory;

    @Inject(required = false)
    private TelemetryServiceTokenValidator telemetryServiceTokenValidator;

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private DataspaceProfileContextRegistry dataspaceProfileContextRegistry;

    @Inject
    private IdentityService identityService;

    @Inject
    private ParticipantAgentService participantAgentService;

    @Inject
    private TelemetryPolicy telemetryPolicy;


    @Override
    public String name() {
        return "Telemetry Service Core";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        policyEngine.registerScope(TELEMETRY_REQUEST_SCOPE, RequestTelemetryPolicyContext.class);
        policyEngine.registerScope(TELEMETRY_SCOPE, TelemetryPolicyContext.class);
    }

    @Provider
    public TelemetryService telemetryService() {
        return new TelemetryServiceImpl(telemetryServiceTokenValidator(), policyEngine, telemetryPolicy, credentialFactory);
    }


    @Provider
    public TelemetryServiceTokenValidator telemetryServiceTokenValidator() {
        if (telemetryServiceTokenValidator == null) {
            telemetryServiceTokenValidator = new TelemetryServiceTokenValidatorImpl(identityService, policyEngine, monitor, participantAgentService, dataspaceProfileContextRegistry);
        }
        return telemetryServiceTokenValidator;
    }


}



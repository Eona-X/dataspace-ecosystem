package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyContextImpl;
import org.eclipse.edc.policy.engine.spi.PolicyScope;


public class TelemetryPolicyContext extends PolicyContextImpl implements ParticipantAgentPolicyContext {

    @PolicyScope
    public static final String TELEMETRY_SCOPE = "telemetry";

    private final ParticipantAgent agent;

    public TelemetryPolicyContext(ParticipantAgent agent) {
        this.agent = agent;
    }

    @Override
    public ParticipantAgent participantAgent() {
        return agent;
    }

    @Override
    public String scope() {
        return TELEMETRY_SCOPE;
    }
}

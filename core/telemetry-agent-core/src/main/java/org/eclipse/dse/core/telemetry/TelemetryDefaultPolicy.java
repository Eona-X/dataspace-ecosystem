package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;


/**
 * Default telemetry policy implementation that provides baseline access permissions
 * for telemetry operations. This policy can be extended or overridden to provide
 * more sophisticated access control.
 */
public class TelemetryDefaultPolicy implements TelemetryPolicy {

    private static final String TELEMETRY_USE_ACTION = "telemetry:use";
    
    @Override
    public Policy get() {
        return Policy.Builder.newInstance()
                .permission(Permission.Builder.newInstance()
                        .action(Action.Builder.newInstance()
                                .type(TELEMETRY_USE_ACTION)
                                .build())
                        .build())
                .build();
    }
}

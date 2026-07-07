package org.eclipse.dse.core.telemetry;

import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;



public class TelemetryAgentDefaultExtension implements ServiceExtension {

    @Provider(isDefault = true)
    public TelemetryPolicy telemetryAgentPolicy() {
        return () -> Policy.Builder.newInstance().build();
    }

}

package org.eclipse.dse.spi.telemetry;

import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.runtime.metamodel.annotation.ExtensionPoint;

@ExtensionPoint
@FunctionalInterface
public interface TelemetryPolicy {
    Policy get();
}

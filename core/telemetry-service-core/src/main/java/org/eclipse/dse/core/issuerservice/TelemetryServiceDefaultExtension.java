package org.eclipse.dse.core.issuerservice;

import org.eclipse.dse.spi.telemetry.TelemetryPolicy;
import org.eclipse.edc.connector.controlplane.profile.DataspaceProfileContextRegistryImpl;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.protocol.spi.DataspaceProfileContextRegistry;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;



public class TelemetryServiceDefaultExtension implements ServiceExtension {

    @Provider(isDefault = true)
    public TelemetryPolicy telemetryServicePolicy() {
        return () -> Policy.Builder.newInstance().build();
    }

    /**
     * Provides a default DataspaceProfileContextRegistry implementation.
     * <p>
     * <b>Limitation:</b> This default implementation does not include any protocol mappings.
     * If protocol extraction is required at runtime, this may lead to failures.
     * It is recommended to provide a more complete implementation with the necessary protocol mappings.
     */
    @Provider(isDefault = true)
    public DataspaceProfileContextRegistry dataspaceProfileContextRegistry() {
        return new DataspaceProfileContextRegistryImpl();
    }

}

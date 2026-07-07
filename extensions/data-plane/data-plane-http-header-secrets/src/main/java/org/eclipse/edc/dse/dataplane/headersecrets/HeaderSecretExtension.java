package org.eclipse.edc.dse.dataplane.headersecrets;

import org.eclipse.edc.connector.dataplane.http.spi.HttpRequestParamsProvider;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(value = HeaderSecretExtension.NAME)
public class HeaderSecretExtension implements ServiceExtension {

    public static final String NAME = "DSE Header Secret Decorator";

    @Inject
    private HttpRequestParamsProvider paramsProvider;

    @Inject
    private Vault vault;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        HeaderSecretParamsDecorator decorator = new HeaderSecretParamsDecorator(vault, context.getMonitor());
        paramsProvider.registerSourceDecorator(decorator);
        paramsProvider.registerSinkDecorator(decorator);
    }
}

package org.eclipse.edc.dse.telemetry.api;

import org.eclipse.dse.spi.telemetry.TelemetryService;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.jersey.mapper.EdcApiExceptionMapper;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import static java.time.Clock.systemUTC;

@Extension(value = TelemetryServiceCredentialApiExtension.NAME)
public class TelemetryServiceCredentialApiExtension implements ServiceExtension {

    public static final String NAME = "Credential API";
    static final int DEFAULT_CREDENTIAL_PORT = 8181;
    static final String DEFAULT_CREDENTIAL_PATH = "/api/credential";
    private static final String API_CONTEXT = "credential";

    @Configuration
    private AdminApiConfiguration apiConfiguration;

    @Inject
    private WebService webService;

    @Inject
    private TelemetryService telemetryService;

    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Inject
    private Vault vault;

    @Inject
    private Monitor monitor;

    @Setting(description = "Public key alias in Vault for JWKS", key = "dse.telemetry.credential.signer.publickey.alias", required = true)
    private String publicKeyAlias;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var portMapping = new PortMapping(API_CONTEXT, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);

        webService.registerResource(API_CONTEXT, new EdcApiExceptionMapper());
        webService.registerResource(API_CONTEXT, new TelemetryServiceCredentialApiController(telemetryService));
        webService.registerResource(API_CONTEXT, new JwksApiController(vault, publicKeyAlias, monitor, systemUTC()));
    }

    @Settings
    record AdminApiConfiguration(
            @Setting(key = "web.http." + API_CONTEXT + ".port", description = "Port for " + API_CONTEXT + " api context", defaultValue = DEFAULT_CREDENTIAL_PORT + "")
            int port,
            @Setting(key = "web.http." + API_CONTEXT + ".path", description = "Path for " + API_CONTEXT + " api context", defaultValue = DEFAULT_CREDENTIAL_PATH)
            String path
    ) {

    }
}

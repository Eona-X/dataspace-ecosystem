package org.eclipse.edc;

import org.eclipse.edc.api.VcCatalogFilterController;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.util.AuthorityCatalogFilterDidResolver;
import org.eclipse.edc.util.FederatedCatalogService;
import org.eclipse.edc.util.VcCatalogInitializer;
import org.eclipse.edc.web.jersey.providers.jsonld.JerseyJsonLdInterceptor;
import org.eclipse.edc.web.jersey.providers.jsonld.ObjectMapperProvider;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import java.net.http.HttpClient;

import static org.eclipse.edc.spi.constants.CoreConstants.JSON_LD;


@Extension("VC-based Catalogue Filter Extension")
public class VcCatalogFilterExtension implements ServiceExtension {

    public static final String NAME = "Federated Catalog Filter API";

    @Inject
    private Monitor monitor;

    @Inject
    private TypeManager typeManager;

    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Inject
    private WebService webService;

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private IdentityService identityService;

    @Inject
    private DidResolverRegistry didResolverRegistry;

    @Inject
    private TypeTransformerRegistry transformerRegistry;

    @Inject
    private JsonLd jsonLd;

    @Configuration
    private CatalogFilterApiConfiguration apiConfiguration;

    @Setting(description = "Authority did", key = "dse.authority.did", required = true)
    public String authorityDid;

    static final String CATALOG_FILTER_SCOPE = "CATALOG_FILTER_API";

    static final String CATALOG_QUERY = "catalog";

    @Override
    public void initialize(ServiceExtensionContext context) {
        monitor.info("Initializing VC Catalogue Filter Extension");
        HttpClient httpClient = HttpClient.newHttpClient();
        VcCatalogInitializer.registerNamespaces(jsonLd, CATALOG_FILTER_SCOPE);
        VcCatalogInitializer.registerTransformers(transformerRegistry);
        portMappingRegistry.register(new PortMapping(CATALOG_QUERY, apiConfiguration.port(), apiConfiguration.path()));
        AuthorityCatalogFilterDidResolver didresolver = new AuthorityCatalogFilterDidResolver(didResolverRegistry, authorityDid);
        FederatedCatalogService catalogService = new FederatedCatalogService(policyEngine, monitor, didresolver, transformerRegistry, jsonLd, httpClient);
        var controller = new VcCatalogFilterController(context, monitor, catalogService, identityService, transformerRegistry);
        webService.registerResource(CATALOG_QUERY, controller);
        webService.registerResource(
                CATALOG_QUERY,
                new ObjectMapperProvider(typeManager, JSON_LD)
        );
        webService.registerResource(
                CATALOG_QUERY,
                new JerseyJsonLdInterceptor(jsonLd, typeManager, JSON_LD, CATALOG_FILTER_SCOPE)
        );
        monitor.info("Registered Federated Catalog Filter");
    }

    @Settings
    record CatalogFilterApiConfiguration(
            @Setting(key = "web.http." + CATALOG_QUERY + ".port", description = "Port for " + CATALOG_QUERY + " api context", defaultValue = 8383 + "")
            int port,
            @Setting(key = "web.http." + CATALOG_QUERY + ".path", description = "Path for " + CATALOG_QUERY + " api context", defaultValue = "/api/catalogfilter")
            String path
    ) {

    }

}

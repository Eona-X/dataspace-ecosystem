package org.eclipse.edc.api;

import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.FilterRequest;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.AbstractResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.util.FederatedCatalogService;
import org.eclipse.edc.util.IdentityServiceValidator;

import java.util.Collection;

import static jakarta.json.stream.JsonCollectors.toJsonArray;

@Path("/filter")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VcCatalogFilterController implements FederatedCatalogFilterApiV2 {

    private final FederatedCatalogService federatedCatalogService;

    private final IdentityService identityService;
    private final TypeTransformerRegistry transformerRegistry;
    private final Monitor monitor;

    public VcCatalogFilterController(ServiceExtensionContext context, Monitor monitor, FederatedCatalogService catalogService, IdentityService identityService, TypeTransformerRegistry transformerRegistry) {
        this.monitor = monitor;
        this.federatedCatalogService = catalogService;
        this.identityService = identityService;
        this.transformerRegistry = transformerRegistry;
    }

    @Override
    @POST
    public Response filter(FilterRequest req) {
        if (req == null || req.tokenRepresentation() == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            Collection<Catalog> filtered = null;
            IdentityServiceValidator validator =  new IdentityServiceValidator(identityService, monitor);
            ClaimToken credentials = validator.validate(req.tokenRepresentation());
            if (credentials != null) {
                filtered = federatedCatalogService.fetchAndFilterCatalog(credentials, req.participantDid());
            }
            if (filtered != null && !filtered.isEmpty()) {
                return Response.ok(filtered.stream()
                        .map(c -> transformerRegistry.transform(c, JsonObject.class))
                        .filter(Result::succeeded)
                        .map(AbstractResult::getContent)
                        .collect(toJsonArray())).build();
            } else {
                return Response.status(Response.Status.NO_CONTENT).build();
            }
        } catch (Exception e) {
            monitor.severe("Error processing catalog filter request", e);
            return Response.status(500).build();
        }
    }

}



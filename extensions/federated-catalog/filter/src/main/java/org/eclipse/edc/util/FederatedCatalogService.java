package org.eclipse.edc.util;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.eclipse.edc.FilterConstants.VC_CLAIMS;


public class FederatedCatalogService {

    private final HttpClient httpClient;
    private final PolicyEngine policyEngine;
    private final Monitor monitor;
    private final AuthorityCatalogFilterDidResolver didResolver;
    private final TypeTransformerRegistry transformerRegistry;
    private final JsonLd jsonLd;

    public FederatedCatalogService(PolicyEngine policyEngine, Monitor monitor, AuthorityCatalogFilterDidResolver didResolver, TypeTransformerRegistry transformerRegistry, JsonLd jsonLd, HttpClient httpClient) {
        this.policyEngine = policyEngine;
        this.httpClient = httpClient;
        this.monitor = monitor;
        this.didResolver = didResolver;
        this.transformerRegistry = transformerRegistry;
        this.jsonLd = jsonLd;
    }

    public Collection<Catalog> fetchAndFilterCatalog(ClaimToken participantVcs, String participantDid) throws Exception {
        CatalogDiscoveryPolicyContext policyContext = createContext(participantVcs);
        Collection<Catalog> catalogs = retrieveCatalog();
        if (catalogs == null || catalogs.isEmpty()) {
            monitor.warning("No catalogs retrieved from the remote source");
            return Collections.emptyList();
        }
        return filterCatalog(catalogs, policyContext, participantDid);
    }

    protected Collection<Catalog> filterCatalog(Collection<Catalog> catalogs, CatalogDiscoveryPolicyContext policyContext, String participantDid) {
        Collection<Catalog> filteredCatalogs = new ArrayList<>();
        for (Catalog catalog : catalogs) {
            if (catalog == null) {
                monitor.warning("Encountered null catalog entry, skipping");
                continue;
            }
            if (catalog.getProperties().values().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(v -> v.contains(participantDid))) {
                filteredCatalogs.add(catalog);
                continue;
            }
            
            List<Dataset> filteredDatasets = catalog.getDatasets().stream()
                    .filter(dataset -> evaluateDatasetPolicy(dataset, policyContext))
                    .collect(Collectors.toList());

            Catalog filteredCatalog = Catalog.Builder.newInstance()
                    .id(catalog.getId())
                    .participantId(catalog.getParticipantId())
                    .datasets(filteredDatasets)
                    .dataServices(catalog.getDataServices())
                    .properties(catalog.getProperties())
                    .build();

            filteredCatalogs.add(filteredCatalog);
        }
        return filteredCatalogs;
    }

    private boolean evaluateDatasetPolicy(Dataset dataset, CatalogDiscoveryPolicyContext policyContext) {
        List<Policy> policies = dataset.getOffers().values().stream().toList();

        if (policies.isEmpty()) {
            monitor.debug(String.format("Dataset %s has no policies", dataset.getId()));
            return true;
        }

        for (Policy policy : policies) {
            Result<Void> evaluationResult = policyEngine.evaluate(policy, policyContext);
            if (!evaluationResult.succeeded()) {
                monitor.debug(String.format("Dataset %s failed policy evaluation", dataset.getId()));
                return false;
            }
        }

        monitor.debug(String.format("Dataset %s passed all policy evaluations", dataset.getId()));
        return true;
    }

    protected CatalogDiscoveryPolicyContext createContext(ClaimToken participantVcs) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(VC_CLAIMS, participantVcs.getClaim(VC_CLAIMS));
        ParticipantAgent agent = new ParticipantAgent(claims, Collections.emptyMap());
        return new CatalogDiscoveryPolicyContext(agent);
    }

    protected Collection<Catalog> retrieveCatalog() throws IOException, InterruptedException {
        Result<String> urlResult = didResolver.fetchCatalogFilterUrl();
        if (urlResult.failed()) {
            throw new RuntimeException("Failed to resolve catalog URL: " + urlResult.getFailureMessages());
        }
        String catalogUrl = urlResult.getContent();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(catalogUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to query catalog: HTTP " + response.statusCode() + " - " + response.body());
        }

        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonArray array = reader.readArray();
            List<Catalog> catalogs = new ArrayList<>(array.size());

            for (var jsonValue : array) {
                if (!(jsonValue instanceof JsonObject jsonObject)) {
                    monitor.warning("Skipping non-object entry in catalog array");
                    continue;
                }

                var expanded = jsonLd.expand(jsonObject);

                if (expanded.failed()) {
                    monitor.warning("Issues with JSON expansion");
                    continue;
                }
                var result = transformerRegistry.transform(expanded.getContent(), Catalog.class);
                if (result.succeeded()) {
                    catalogs.add(result.getContent());
                } else {
                    monitor.warning("Failed to transform catalog entry: " + String.join("; ", result.getFailureMessages()));
                }
            }
            return catalogs;
        }
    }
}
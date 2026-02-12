package org.eclipse.edc.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.iam.core.DefaultScopeExtractor;
import org.eclipse.dse.iam.core.RequestCatalogDiscoveryContext;
import org.eclipse.dse.iam.policy.CatalogDiscoveryConstraintFunction;
import org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext;
import org.eclipse.edc.catalog.transform.JsonObjectToCatalogTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDataServiceTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDatasetTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDistributionTransformer;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToActionTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToConstraintTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToOperatorTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPermissionTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPolicyTransformer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialSubject;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.Issuer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.participant.spi.ParticipantIdMapper;
import org.eclipse.edc.policy.engine.PolicyEngineImpl;
import org.eclipse.edc.policy.engine.RuleBindingRegistryImpl;
import org.eclipse.edc.policy.engine.ScopeFilter;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.engine.validation.RuleValidator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.transform.transformer.edc.to.JsonObjectToCriterionTransformer;
import org.eclipse.edc.transform.transformer.edc.to.JsonObjectToQuerySpecTransformer;
import org.eclipse.edc.transform.transformer.edc.to.JsonValueToGenericTypeTransformer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DOMAIN_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.spi.constants.CoreConstants.JSON_LD;
import static org.eclipse.edc.util.IdentityServiceValidator.READ_ALL_CREDENTIAL_SCOPE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FederatedCatalogFilterServiceTest {

    private static AuthorityCatalogFilterDidResolver didResolverRegistry = mock();
    private static PolicyEngine policyEngine = null;
    private static RuleBindingRegistry registry = new RuleBindingRegistryImpl();
    private static final String ISSUER = "did:web:issuer";
    private static final String SUBJECT = "did:web:subject";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Monitor monitor = mock();
    private static final String PARTICIPANT_DID = "did:web:participant";
    private static ParticipantIdMapper participantIdMapper = mock(ParticipantIdMapper.class);
    private static final String CATALOG_REPLY = "/catalogReply.txt";
    private static TypeTransformerRegistry transformerRegistry = new TypeTransformerRegistryImpl();
    private final JsonLd jsonLd = new TitaniumJsonLd(monitor);
    private static HttpClient httpClient = mock(HttpClient.class);
    private static HttpResponse<String> httpResponse = mock(HttpResponse.class);
    private static TypeManager typeManager = mock(TypeManager.class);


    @BeforeAll
    static void init() throws IOException, InterruptedException {
        policyEngine = new PolicyEngineImpl(new ScopeFilter(registry), new RuleValidator(registry));
        policyEngine.registerPostValidator(RequestCatalogDiscoveryContext.class, new DefaultScopeExtractor<>(Set.of(READ_ALL_CREDENTIAL_SCOPE)));
        registry.bind(ODRL_USE_ACTION_ATTRIBUTE,  CATALOG_DISCOVERY_SCOPE);
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        registry.dynamicBind(s -> s.startsWith(DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT) ? Set.of(CATALOG_DISCOVERY_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        registerTransformers();
        when(didResolverRegistry.fetchCatalogFilterUrl()).thenReturn(Result.success("http://example.com/catalog"));
        String reply = loadCatalogFromFile(CATALOG_REPLY);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(reply);
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);
    }

    private static void registerTransformers() {
        transformerRegistry.register(new JsonObjectToCatalogTransformer());
        transformerRegistry.register(new JsonObjectToDatasetTransformer());
        transformerRegistry.register(new JsonObjectToPolicyTransformer(participantIdMapper));
        transformerRegistry.register(new JsonObjectToDataServiceTransformer());
        transformerRegistry.register(new JsonObjectToDistributionTransformer());
        transformerRegistry.register(new JsonObjectToQuerySpecTransformer());
        transformerRegistry.register(new JsonObjectToCriterionTransformer());
        transformerRegistry.register(new JsonObjectToPermissionTransformer());
        transformerRegistry.register(new JsonObjectToActionTransformer());
        transformerRegistry.register(new JsonObjectToConstraintTransformer());
        transformerRegistry.register(new JsonObjectToOperatorTransformer());
        transformerRegistry.register(new JsonValueToGenericTypeTransformer(typeManager, JSON_LD));
    }

    @Test
    void filterCatalogSimplePolicies() throws IOException {
        var membershipVc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var domainVc = createVc(DOMAIN_CREDENTIAL_TYPE, Map.of("domain", "route"));
        var credentials = List.of(membershipVc, domainVc);
        ClaimToken tokens = ClaimToken.Builder.newInstance().claim("vc", credentials).build();
        FederatedCatalogService service = new FederatedCatalogService(policyEngine, monitor, didResolverRegistry, transformerRegistry, jsonLd, httpClient);
        Collection<Catalog> catalogs = new ArrayList<>();
        try {
            catalogs = service.retrieveCatalog();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Collection<Catalog> result = service.filterCatalog(catalogs, service.createContext(tokens), PARTICIPANT_DID);
        monitor.warning(result.toString());
        assertFalse(result.isEmpty());
        assertTrue(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("restricted-route-asset")))
        );
        assertFalse(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("restricted-travel-asset")))
        );
        assertTrue(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("visible-restricted-asset")))
        );
        assertFalse(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("restricted-and-asset")))
        );
        assertTrue(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("visible-list-asset")))
        );
        assertFalse(
                result.stream()
                        .anyMatch(c -> c.getDatasets().stream()
                                .anyMatch(d -> d.getId().equals("restricted-list-asset")))
        );
    }

    private static VerifiableCredential createVc(String type, Map<String, Object> claims) {
        return VerifiableCredential.Builder.newInstance()
                .type(type)
                .issuer(new Issuer(ISSUER))
                .issuanceDate(Instant.now())
                .credentialSubject(CredentialSubject.Builder.newInstance().claims(claims).id(SUBJECT).build())
                .build();
    }

    private static String loadCatalogFromFile(String resourcePath) throws IOException {
        String catalogReply;
        try (var is = FederatedCatalogFilterServiceTest.class.getResourceAsStream("/catalogReply.txt")) {
            catalogReply = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        return catalogReply;
    }
}

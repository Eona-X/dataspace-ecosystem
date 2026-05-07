package org.eclipse.dse.iam.policy;

import org.eclipse.dse.spi.telemetry.TelemetryPolicyContext;
import org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.List;
import java.util.Set;

import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_GENERIC_CLAIM_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_MEMBERSHIP_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.dse.spi.telemetry.TelemetryPolicyContext.TELEMETRY_SCOPE;
import static org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext.CATALOG_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_NS;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_PREFIX;

public class PolicyEvaluationExtension implements ServiceExtension {

    private static final Set<String> RULE_SCOPES = Set.of(
            TRANSFER_SCOPE,
            CATALOG_SCOPE,
            NEGOTIATION_SCOPE,
            CATALOG_DISCOVERY_SCOPE,
            TELEMETRY_SCOPE
    );

    private static final List<String> ADDITIONAL_ODRL_ACTIONS = List.of(
            ODRL_SCHEMA + "transfer",
            ODRL_SCHEMA + "share",
            ODRL_SCHEMA + "distribute"
    );

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;

    @Inject
    private JsonLd jsonLdService;

    @Inject
    private Monitor monitor;


    @Override
    public void initialize(ServiceExtensionContext context) {
        registerNamespaces();
        registerFunctions();
        registerBindings();
    }

    private void registerNamespaces() {
        jsonLdService.registerNamespace(DSE_POLICY_PREFIX, DSE_POLICY_NS);
    }

    private void registerFunctions() {
        policyEngine.registerFunction(CatalogPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(TelemetryPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());

        policyEngine.registerFunction(CatalogPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(TelemetryPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
    }

    private void registerBindings() {
        ruleBindingRegistry.dynamicBind(s -> s.startsWith(DSE_GENERIC_CLAIM_CONSTRAINT) ? Set.of(NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        ruleBindingRegistry.dynamicBind(s -> s.startsWith(DSE_MEMBERSHIP_CONSTRAINT) ? RULE_SCOPES : Set.of());
        ruleBindingRegistry.dynamicBind(s -> s.startsWith(DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT) ? Set.of(CATALOG_DISCOVERY_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        RULE_SCOPES.forEach(scope -> ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, scope));
        
        for (var action : ADDITIONAL_ODRL_ACTIONS) {
            RULE_SCOPES.forEach(scope -> ruleBindingRegistry.bind(action, scope));
        }
    }

}

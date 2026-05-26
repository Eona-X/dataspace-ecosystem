package org.eclipse.dse.iam.policy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.spi.telemetry.TelemetryPolicyContext.TELEMETRY_SCOPE;
import static org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext.CATALOG_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;


class PolicyEvaluationExtensionTest {

    @Test
    void ruleScopes_shouldContainAllExpectedScopes_noRegression() throws Exception {
        // Given - Accès à la constante via réflexion
        Field field = PolicyEvaluationExtension.class.getDeclaredField("RULE_SCOPES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> scopes = (Set<String>) field.get(null);

        // Then - Vérifier qu'aucun scope n'a été perdu
        assertThat(scopes)
                .hasSize(4)
                .containsExactlyInAnyOrder(
                        TRANSFER_SCOPE,
                        CATALOG_SCOPE,
                        NEGOTIATION_SCOPE,
                        CATALOG_DISCOVERY_SCOPE
                );
    }

    @Test
    void additionalOdrlActions_shouldContainExactlyThreeActions_noRegression() throws Exception {
        // Given - Accès à la constante via réflexion
        Field field = PolicyEvaluationExtension.class.getDeclaredField("ADDITIONAL_ODRL_ACTIONS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) field.get(null);

        // Then - Vérifier qu'il y a exactement 3 actions
        assertThat(actions).hasSize(3);
    }

    @Test
    void additionalOdrlActions_shouldContainTransferShareDistribute_noRegression() throws Exception {
        // Given - Accès à la constante via réflexion
        Field field = PolicyEvaluationExtension.class.getDeclaredField("ADDITIONAL_ODRL_ACTIONS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) field.get(null);

        // Then - Vérifier que les 3 actions originales sont toujours présentes
        assertThat(actions)
                .contains(ODRL_SCHEMA + "transfer")
                .contains(ODRL_SCHEMA + "share")
                .contains(ODRL_SCHEMA + "distribute");
    }

    @Test
    void additionalOdrlActions_shouldAllStartWithOdrlSchema_noRegression() throws Exception {
        // Given - Accès à la constante via réflexion
        Field field = PolicyEvaluationExtension.class.getDeclaredField("ADDITIONAL_ODRL_ACTIONS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) field.get(null);

        // Then - Vérifier que toutes les actions ont le bon préfixe
        assertThat(actions).allMatch(action -> action.startsWith(ODRL_SCHEMA));
    }

    @Test
    void constants_shouldNotBeNull_noRegression() throws Exception {
        // Given - Accès aux constantes via réflexion
        Field actionsField = PolicyEvaluationExtension.class.getDeclaredField("ADDITIONAL_ODRL_ACTIONS");
        Field scopesField = PolicyEvaluationExtension.class.getDeclaredField("RULE_SCOPES");
        actionsField.setAccessible(true);
        scopesField.setAccessible(true);

        // When
        List<String> actions = (List<String>) actionsField.get(null);
        Set<String> scopes = (Set<String>) scopesField.get(null);

        // Then - Vérifier que les constantes ne sont pas nulles
        assertThat(actions).isNotNull();
        assertThat(scopes).isNotNull();
    }

    @Test
    void extensionClass_shouldExist_noRegression() {
        // Then - Vérifier que la classe existe et peut être instanciée
        assertThat(PolicyEvaluationExtension.class).isNotNull();
        
        // When - Instanciation
        PolicyEvaluationExtension extension = new PolicyEvaluationExtension();
        
        // Then - Vérifier que l'instanciation fonctionne
        assertThat(extension).isNotNull();
    }
}

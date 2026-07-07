package org.eclipse.dse.core.kafkaproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.core.kafkaproxy.model.EdrProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VaultServiceJaasTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractUsernameFromJaas_validConfigWithDoubleQuotes_returnsUsername() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("user1", vaultService.extractUsernameFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"user1\" password=\"pass1\";"));
    }

    @Test
    void extractUsernameFromJaas_validConfigWithSingleQuotes_returnsUsername() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("user2", vaultService.extractUsernameFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required username='user2' password='pass2';"));
    }

    @Test
    void extractUsernameFromJaas_configWithoutUsername_returnsNull() throws Exception {
        VaultService vaultService = createVaultService();

        assertNull(vaultService.extractUsernameFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required password=\"pass1\";"));
    }

    @Test
    void extractUsernameFromJaas_nullConfig_returnsNull() throws Exception {
        VaultService vaultService = createVaultService();

        assertNull(vaultService.extractUsernameFromJaas((String) null));
    }

    @Test
    void extractPasswordFromJaas_validConfigWithDoubleQuotes_returnsPassword() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("pass1", vaultService.extractPasswordFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"user1\" password=\"pass1\";"));
    }

    @Test
    void extractPasswordFromJaas_validConfigWithSingleQuotes_returnsPassword() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("pass2", vaultService.extractPasswordFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required username='user2' password='pass2';"));
    }

    @Test
    void extractPasswordFromJaas_configWithoutPassword_returnsNull() throws Exception {
        VaultService vaultService = createVaultService();

        assertNull(vaultService.extractPasswordFromJaas("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"user1\";"));
    }

    @Test
    void extractOauth2ClientIdFromJaas_validConfigWithDoubleQuotes_returnsClientId() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("abc", vaultService.extractOauth2ClientIdFromJaas("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=\"abc\" clientSecret=\"xyz\";"));
    }

    @Test
    void extractOauth2ClientIdFromJaas_validConfigWithSingleQuotes_returnsClientId() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("abc", vaultService.extractOauth2ClientIdFromJaas("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId='abc' clientSecret='xyz';"));
    }

    @Test
    void extractOauth2ClientSecretFromJaas_validConfig_returnsClientSecret() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("xyz", vaultService.extractOauth2ClientSecretFromJaas("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=\"abc\" clientSecret=\"xyz\";"));
    }

    @Test
    void extractOauth2TenantIdFromJaas_validConfig_returnsTenantId() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("tenant-123", vaultService.extractOauth2TenantIdFromJaas("module required tenantId=\"tenant-123\";"));
    }

    @Test
    void extractOauth2ScopeFromJaas_validConfig_returnsScope() throws Exception {
        VaultService vaultService = createVaultService();

        assertEquals("openid", vaultService.extractOauth2ScopeFromJaas("module required scope=\"openid\";"));
    }

    @Test
    void parseEdrProperties_validJsonWithAllFields_returnsEdrProperties() throws Exception {
        VaultService vaultService = createVaultService();

        String json = "{" +
                "  \"properties\": {" +
                "    \"https://w3id.org/edc/v0.0.1/ns/kafka.bootstrap.servers\": \"kafka:9092\"," +
                "    \"https://w3id.org/edc/v0.0.1/ns/security.protocol\": \"SASL_SSL\"," +
                "    \"https://w3id.org/edc/v0.0.1/ns/sasl.mechanism\": \"PLAIN\"," +
                "    \"https://w3id.org/edc/v0.0.1/ns/tls_ca_crt\": \"my-ca-cert\"," +
                "    \"https://w3id.org/edc/v0.0.1/ns/sasl.jaas.config\": \"org.apache.kafka.common.security.plain.PlainLoginModule required username=\\\"user\\\" password=\\\"pass\\\";\"" +
                "  }" +
                "}";
        JsonNode node = objectMapper.readTree(json);

        EdrProperties props = vaultService.parseEdrProperties(node);

        assertEquals("kafka:9092", props.getBootstrapServers());
        assertEquals("user", props.getUsername());
        assertEquals("pass", props.getPassword());
        assertEquals("SASL_SSL", props.getSecurityProtocol());
        assertEquals("PLAIN", props.getSaslMechanism());
        assertEquals("my-ca-cert", props.getTlsCaCrt());
    }

    private VaultService createVaultService() {
        // We use dummy values since we only test the private JAAS extraction methods which don't use the vault client
        try {
            return new VaultService("http://localhost:8200", "token", "folder");
        } catch (Exception e) {
            // If the constructor fails because it tries to connect, we might need to mock it or use a simpler way
            // But usually constructors for these services don't connect immediately.
            // In VaultService, it creates a Vault object.
            return null;
        }
    }
}

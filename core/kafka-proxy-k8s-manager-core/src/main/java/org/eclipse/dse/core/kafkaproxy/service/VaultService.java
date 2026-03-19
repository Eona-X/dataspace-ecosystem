/*
 *  Copyright (c) 2024 Eclipse Dataspace Connector Project
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Eclipse Dataspace Connector Project - initial implementation
 */

package org.eclipse.dse.core.kafkaproxy.service;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.core.kafkaproxy.model.EdrDiscoveryResult;
import org.eclipse.dse.core.kafkaproxy.model.EdrProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Service for retrieving EDR (Endpoint Data Reference) properties from HashiCorp Vault
 * Now enhanced with automatic discovery capabilities
 */
public class VaultService {
    
    private static final Logger LOGGER = Logger.getLogger(VaultService.class.getName());
    
    private final Vault vault;
    private final ObjectMapper objectMapper;
    private final Map<String, EdrProperties> cache = new ConcurrentHashMap<>();
    private final VaultDiscoveryService discoveryService;
    private final Set<String> lastKnownEdrKeys = new HashSet<>();
    private boolean hasInitialScan = false;
    private final String vaultFolder;
    
    public VaultService(String vaultAddress, String vaultToken, String vaultFolder) {
        try {
            if (vaultToken == null || vaultToken.trim().isEmpty()) {
                LOGGER.severe("Vault token is missing! Vault authentication will fail.");
                throw new IllegalArgumentException("Vault token cannot be null or empty");
            }
            
            // Configure SSL to disable verification for self-signed certificates
            SslConfig sslConfig = new SslConfig()
                    .verify(false)  // Disable SSL verification for self-signed certificates
                    .build();
            
            VaultConfig config = new VaultConfig()
                    .address(vaultAddress)
                    .token(vaultToken)
                    .sslConfig(sslConfig)  // Apply SSL config
                    .build();
            this.vault = new Vault(config);
            this.objectMapper = new ObjectMapper();
            this.vaultFolder = (vaultFolder == null || vaultFolder.trim().isEmpty()) ? "" : vaultFolder.trim();
            this.discoveryService = new VaultDiscoveryService(vaultAddress, vaultToken, vaultFolder);
            String folderInfo = this.vaultFolder.isEmpty() ? "(root)" : this.vaultFolder;
            LOGGER.info(format("VaultService initialized with vault at %s, folder: %s (SSL verification disabled)", vaultAddress, folderInfo));
        } catch (VaultException e) {
            LOGGER.severe(format("Failed to initialize Vault client: %s", e.getMessage()));
            throw new RuntimeException("Failed to initialize Vault client", e);
        }
    }
    
    /**
     * Retrieves EDR properties from Vault for the given EDR key
     */
    public EdrProperties getEdrProperties(String edrKey) {
        // Check cache first
        if (cache.containsKey(edrKey)) {
            LOGGER.info(format("Retrieved cached EDR properties for: %s", edrKey));
            return cache.get(edrKey);
        }
        
        try {
            // Build folder-aware path: secret/folder/edrKey or secret/edrKey
            String secretPath = vaultFolder.isEmpty() 
                    ? format("secret/%s", edrKey)
                    : format("secret/%s/%s", vaultFolder, edrKey);
            
            LOGGER.info(format("Retrieving EDR properties from Vault for: %s (path: %s)", edrKey, secretPath));
            
            // Get secret from Vault KV store
            Map<String, String> secretData = vault.logical()
                    .read(secretPath)
                    .getData();
            
            if (secretData == null || secretData.isEmpty()) {
                LOGGER.warning(format("No secret data found for EDR key: %s (path: %s)", edrKey, secretPath));
                return getDefaultProperties();
            }
            
            // Parse the nested JSON structure
            String contentJson = secretData.get("content");
            if (contentJson == null) {
                LOGGER.warning(format("No content field found in secret for EDR key: %s", edrKey));
                return getDefaultProperties();
            }
            
            JsonNode contentNode = objectMapper.readTree(contentJson);
            EdrProperties properties = parseEdrProperties(contentNode);
            
            // Cache the result
            cache.put(edrKey, properties);
            
            LOGGER.info(format("Successfully retrieved EDR properties for: %s - Protocol: %s, Bootstrap: %s", 
                    edrKey, properties.getSecurityProtocol(), properties.getBootstrapServers()));
            
            return properties;
            
        } catch (VaultException | JsonProcessingException e) {
            LOGGER.severe(format("Failed to retrieve EDR properties for %s: %s", edrKey, e.getMessage()));
            return getDefaultProperties();
        }
    }
    
    private EdrProperties parseEdrProperties(JsonNode contentNode) {
        // Try to extract from both EDC properties and DataAddress properties
        JsonNode properties = contentNode.get("properties");
        JsonNode dataAddress = contentNode.at("/dataAddress/properties");
        
        String bootstrapServers = getPropertyValue(properties, dataAddress, 
                "https://w3id.org/edc/v0.0.1/ns/kafka.bootstrap.servers", "kafka.bootstrap.servers");
        String topic = getPropertyValue(properties, dataAddress, 
                "https://w3id.org/edc/v0.0.1/ns/topic", "topic");
        String securityProtocol = getPropertyValue(properties, dataAddress, 
                "https://w3id.org/edc/v0.0.1/ns/security.protocol", "security.protocol");
        String saslMechanism = getPropertyValue(properties, dataAddress, 
                "https://w3id.org/edc/v0.0.1/ns/sasl.mechanism", "sasl.mechanism");
        String saslJaasConfig = getPropertyValue(properties, dataAddress, 
                "https://w3id.org/edc/v0.0.1/ns/sasl.jaas.config", "sasl.jaas.config");
        
        // Extract username and password from JAAS config if available (for SASL PLAIN)
        String username = extractUsernameFromJaas(saslJaasConfig);
        String password = extractPasswordFromJaas(saslJaasConfig);

        securityProtocol = securityProtocol != null ? securityProtocol : "PLAINTEXT";
        saslMechanism = securityProtocol.startsWith("SASL")
                ? (saslMechanism != null ? saslMechanism : "PLAIN")
                : "NONE";
        
        // Extract OAuth2 credentials from JAAS config if available (for OAUTHBEARER)
        String oauth2ClientId = extractOauth2ClientIdFromJaas(saslJaasConfig);
        String oauth2ClientSecret = extractOauth2ClientSecretFromJaas(saslJaasConfig);
        String oauth2TenantId = extractOauth2TenantIdFromJaas(saslJaasConfig);
        String oauth2Scope = extractOauth2ScopeFromJaas(saslJaasConfig);
        
        return new EdrProperties(
                bootstrapServers != null ? bootstrapServers : "",
                username,
                password,
                securityProtocol,
                saslMechanism,
                null, // tls_client_cert - default to one-way TLS
                null, // tls_client_key - default to one-way TLS
                null, // tls_ca_secret - default to "kafka-tls-ca"
                oauth2ClientId,
                oauth2ClientSecret,
                oauth2TenantId,
                oauth2Scope
        );
    }
    
    private String getPropertyValue(JsonNode properties, JsonNode dataAddress, String edcKey, String fallbackKey) {
        String value = null;
        
        if (properties != null && properties.has(edcKey)) {
            value = properties.get(edcKey).asText();
        }
        
        if ((value == null || value.isEmpty()) && dataAddress != null && dataAddress.has(fallbackKey)) {
            value = dataAddress.get(fallbackKey).asText();
        }
        
        return value;
    }
    
    private String extractUsernameFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        
        // Extract username from JAAS config: org.apache.kafka.common.security.plain.PlainLoginModule required username="user" password="pass";
        // Handle both single and double quotes
        String username = extractQuotedValue(jaasConfig, "username=");
        return username;
    }
    
    private String extractPasswordFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        
        // Extract password from JAAS config
        // Handle both single and double quotes
        String password = extractQuotedValue(jaasConfig, "password=");
        return password;
    }
    
    /**
     * Extract a quoted value from JAAS config, handling both single and double quotes
     *
     * @param jaasConfig The JAAS configuration string
     * @param prefix The prefix to look for (e.g., "username=", "password=")
     * @return The extracted value or null if not found
     */
    private String extractQuotedValue(String jaasConfig, String prefix) {
        int start = jaasConfig.indexOf(prefix);
        if (start == -1) {
            return null;
        }
        
        start += prefix.length();
        
        // Check for quote character (single or double)
        if (start >= jaasConfig.length()) {
            return null;
        }
        
        char quoteChar = jaasConfig.charAt(start);
        if (quoteChar != '\"' && quoteChar != '\'') {
            return null;
        }
        
        start++; // move past the opening quote
        int end = jaasConfig.indexOf(quoteChar, start);
        if (end == -1) {
            return null;
        }
        
        return jaasConfig.substring(start, end);
    }
    
    /**
     * Extract OAuth2 client ID from JAAS config for OAUTHBEARER authentication.
     * Example: org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId="abc" clientSecret="xyz";
     */
    private String extractOauth2ClientIdFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        return extractQuotedValue(jaasConfig, "clientId=");
    }
    
    /**
     * Extract OAuth2 client secret from JAAS config for OAUTHBEARER authentication.
     */
    private String extractOauth2ClientSecretFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        return extractQuotedValue(jaasConfig, "clientSecret=");
    }
    
    /**
     * Extract OAuth2 tenant ID from JAAS config for OAUTHBEARER authentication.
     */
    private String extractOauth2TenantIdFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        return extractQuotedValue(jaasConfig, "tenantId=");
    }
    
    /**
     * Extract OAuth2 scope from JAAS config for OAUTHBEARER authentication.
     */
    private String extractOauth2ScopeFromJaas(String jaasConfig) {
        if (jaasConfig == null || jaasConfig.isEmpty()) {
            return null;
        }
        return extractQuotedValue(jaasConfig, "scope=");
    }
    
    private EdrProperties getDefaultProperties() {
        LOGGER.warning("Returning default EDR properties");
        return new EdrProperties(
                "", // empty bootstrap servers to force error instead of wrong default
                null,
                null,
                "SASL_SSL",
                "PLAIN",
                null, // tls_client_cert - default to one-way TLS
                null, // tls_client_key - default to one-way TLS
                null, // tls_ca_secret - default to "kafka-tls-ca"
                null, // oauth2_client_id
                null, // oauth2_client_secret
                null, // oauth2_tenant_id
                null  // oauth2_scope
        );
    }
    
    /**
     * Performs automatic discovery of Kafka EDRs in Vault.
     *
     * @return List of discovered Kafka EDRs ready for deployment
     */
    public List<EdrDiscoveryResult> discoverKafkaEdrs() {
        return discoveryService.performDiscoveryCycle();
    }
    
    /**
     * Gets all current EDR keys from Vault for cleanup purposes
     */
    public List<String> getCurrentEdrKeys() {
        return discoveryService.getCurrentEdrKeys();
    }
    
    /**
     * Checks if an EDR has already been processed by the discovery service
     */
    public boolean isEdrAlreadyProcessed(String edrKey) {
        return discoveryService.isEdrAlreadyProcessed(edrKey);
    }
    
    /**
     * Converts a discovery result to EDR properties for deployment
     */
    public EdrProperties convertToEdrProperties(EdrDiscoveryResult discoveryResult) {
        if (discoveryResult == null || discoveryResult.getKafkaProperties() == null) {
            return getDefaultProperties();
        }
        
        VaultDiscoveryService.KafkaProperties kafkaProps = discoveryResult.getKafkaProperties();
        
        // Extract username and password from JAAS config if available (for SASL PLAIN)
        String username = extractUsernameFromJaas(kafkaProps.getSaslJaasConfig());
        String password = extractPasswordFromJaas(kafkaProps.getSaslJaasConfig());
        
        // Extract OAuth2 credentials from JAAS config if available (for OAUTHBEARER)
        String oauth2ClientId = extractOauth2ClientIdFromJaas(kafkaProps.getSaslJaasConfig());
        String oauth2ClientSecret = extractOauth2ClientSecretFromJaas(kafkaProps.getSaslJaasConfig());
        String oauth2TenantId = extractOauth2TenantIdFromJaas(kafkaProps.getSaslJaasConfig());
        String oauth2Scope = extractOauth2ScopeFromJaas(kafkaProps.getSaslJaasConfig());
        
        return new EdrProperties(
                kafkaProps.getBootstrapServers(),
                username,
                password,
                kafkaProps.getSecurityProtocol(),
                kafkaProps.getSaslMechanism(),
                null, // tls_client_cert - default to one-way TLS
                null, // tls_client_key - default to one-way TLS
                null, // tls_ca_secret - default to "kafka-tls-ca"
                oauth2ClientId,
                oauth2ClientSecret,
                oauth2TenantId,
                oauth2Scope
        );
    }
    
    /**
     * Detects EDR keys that have been deleted from Vault since last check
     * This enables automatic cleanup of orphaned proxy deployments
     */
    public List<String> getDeletedEdrKeys() {
        List<String> deletedKeys = new ArrayList<>();
        
        try {
            List<String> currentKeys = getCurrentEdrKeys();
            Set<String> currentKeySet = new HashSet<>(currentKeys);
            
            // Always perform deletion detection if we have previously known keys
            if (!lastKnownEdrKeys.isEmpty()) {
                // Find keys that were present before but are missing now
                for (String previousKey : lastKnownEdrKeys) {
                    if (!currentKeySet.contains(previousKey)) {
                        deletedKeys.add(previousKey);
                        LOGGER.info(format("Detected deleted EDR key: %s (was in vault, now missing)", previousKey));
                    }
                }
                
                if (!deletedKeys.isEmpty()) {
                    LOGGER.info(format("Found %d deleted EDR keys: %s", deletedKeys.size(), deletedKeys));
                }
            } else if (!hasInitialScan) {
                LOGGER.info("Initial EDR tracking scan - no deletions to detect yet");
            }
            
            // Update the known keys for next comparison
            lastKnownEdrKeys.clear();
            lastKnownEdrKeys.addAll(currentKeySet);
            hasInitialScan = true;
            
        } catch (Exception e) {
            LOGGER.severe(format("Failed to detect deleted EDR keys: %s", e.getMessage()));
        }
        
        return deletedKeys;
    }
    
    /**
     * Checks if a specific EDR key still exists in Vault
     */
    public boolean edrKeyExists(String edrKey) {
        try {
            return getCurrentEdrKeys().contains(edrKey);
        } catch (Exception e) {
            LOGGER.warning(format("Failed to check if EDR key exists %s: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Forces a refresh of the known EDR keys (useful for initialization)
     */
    public void refreshKnownEdrKeys() {
        try {
            List<String> currentKeys = getCurrentEdrKeys();
            lastKnownEdrKeys.clear();
            lastKnownEdrKeys.addAll(currentKeys);
            hasInitialScan = true;
            LOGGER.info(format("Refreshed known EDR keys, now tracking %d keys", currentKeys.size()));
        } catch (Exception e) {
            LOGGER.severe(format("Failed to refresh known EDR keys: %s", e.getMessage()));
        }
    }
    
    /**
     * Tag a secret as deployed using custom metadata
     */
    public boolean tagSecretAsDeployed(String edrKey, String proxyName, boolean deployed) {
        try {
            // For now, log the tagging action - vault metadata implementation depends on vault setup
            LOGGER.info(format("Tagging secret %s as deployed=%s with proxy name %s", 
                    edrKey, deployed, proxyName));
            return true;
            
        } catch (Exception e) {
            LOGGER.severe(format("Failed to tag secret %s as deployed: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Check if a secret is tagged as deployed
     */
    public boolean isSecretTaggedAsDeployed(String edrKey) {
        try {
            // For now, return false - implementation depends on vault metadata setup
            LOGGER.fine(format("Checking deployment tag for %s", edrKey));
            return false;
            
        } catch (Exception e) {
            LOGGER.warning(format("Failed to check deployment tag for %s: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Remove deployment tag from a secret
     */
    public boolean untagSecret(String edrKey) {
        try {
            LOGGER.info(format("Untagging secret %s (removing deployment tags)", edrKey));
            return true;
            
        } catch (Exception e) {
            LOGGER.severe(format("Failed to untag secret %s: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    /**
     * Gets the creation timestamp of an EDR from vault metadata.
     * Returns 0 if timestamp cannot be retrieved.
     */
    public long getEdrCreationTimestamp(String edrKey) {
        try {
            return discoveryService.getEdrCreationTimestamp(edrKey);
        } catch (Exception e) {
            LOGGER.warning(format("Failed to get creation timestamp for EDR %s: %s", edrKey, e.getMessage()));
            return 0;
        }
    }
    
    /**
     * Clears both properties and discovery caches
     */
    public void clearCache() {
        cache.clear();
        discoveryService.clearCache();
        lastKnownEdrKeys.clear();
        hasInitialScan = false;
        LOGGER.info("EDR properties and discovery caches cleared");
    }
}
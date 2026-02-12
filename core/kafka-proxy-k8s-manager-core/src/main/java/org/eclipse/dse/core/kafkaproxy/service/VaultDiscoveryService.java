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

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.response.LogicalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.dse.core.kafkaproxy.model.EdrDiscoveryResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static java.lang.String.format;

/**
 * Service for automatic discovery of Kafka EDRs from HashiCorp Vault
 */
public class VaultDiscoveryService {
    
    private static final Logger LOGGER = Logger.getLogger(VaultDiscoveryService.class.getName());
    private static final String EDR_PREFIX = "edr--";
    private static final String KAFKA_TYPE = "Kafka";
    
    private final Vault vault;
    private final String vaultAddress;
    private final String vaultToken;
    private final String vaultFolder;
    private final ObjectMapper objectMapper;
    private final Map<String, EdrDiscoveryResult> discoveryCache = new ConcurrentHashMap<>();
    
    public VaultDiscoveryService(String vaultAddress, String vaultToken, String vaultFolder) {
        try {
            VaultConfig config = new VaultConfig()
                    .address(vaultAddress)
                    .token(vaultToken)
                    .build();
            this.vault = new Vault(config);
            this.vaultAddress = vaultAddress;
            this.vaultToken = vaultToken;
            this.vaultFolder = (vaultFolder == null || vaultFolder.trim().isEmpty()) ? "" : vaultFolder.trim();
            this.objectMapper = new ObjectMapper();
            String folderInfo = this.vaultFolder.isEmpty() ? "(root)" : this.vaultFolder;
            LOGGER.info(format("VaultDiscoveryService initialized with vault at: %s, folder: %s (SSL verification disabled)", vaultAddress, folderInfo));
        } catch (VaultException e) {
            throw new RuntimeException("Failed to initialize Vault client for discovery", e);
        }
    }
    
    /**
     * Get the vault path for listing secrets (with folder support)
     */
    private String getSecretListPath() {
        return vaultFolder.isEmpty() ? "secret/" : "secret/" + vaultFolder + "/";
    }
    
    /**
     * Get the vault path for reading a secret (with folder support)
     */
    private String getSecretPath(String edrKey) {
        return vaultFolder.isEmpty() ? "secret/" + edrKey : "secret/" + vaultFolder + "/" + edrKey;
    }
    
    /**
     * Get the vault HTTP API path for metadata (with folder support)
     */
    private String getMetadataApiPath(String edrKey) {
        return vaultFolder.isEmpty()
                ? "/v1/secret/metadata/" + edrKey
                : "/v1/secret/metadata/" + vaultFolder + "/" + edrKey;
    }
    
    /**
     * Performs a complete discovery cycle.
     *
     * @return List of discovered EDR results from Vault with Kafka properties extracted and validation status
     */
    public List<EdrDiscoveryResult> performDiscoveryCycle() {
        LOGGER.info("Starting vault discovery cycle...");
        
        try {
            // Check vault connection first
            if (!checkVaultConnection()) {
                LOGGER.severe("Vault connection failed, aborting discovery cycle");
                return List.of();
            }
            
            // Get all EDR keys from vault
            List<String> edrKeys = getEdrKeys();
            if (edrKeys.isEmpty()) {
                LOGGER.info("No EDR keys found in vault");
                return List.of();
            }
            
            LOGGER.info(format("Found %d EDR keys, processing...", edrKeys.size()));
            
            List<EdrDiscoveryResult> kafkaEdrs = new ArrayList<>();
            
            // Process each EDR key
            for (String edrKey : edrKeys) {
                if (edrKey != null && !edrKey.trim().isEmpty()) {
                    EdrDiscoveryResult result = processEdrKey(edrKey);
                    if (result != null && result.isKafkaEdr() && result.isReadyForDeployment()) {
                        kafkaEdrs.add(result);
                        // Cache the result
                        discoveryCache.put(edrKey, result);
                    }
                }
            }
            
            LOGGER.info(format("Discovery cycle completed. Found %d Kafka EDRs ready for deployment", kafkaEdrs.size()));
            return kafkaEdrs;
            
        } catch (Exception e) {
            LOGGER.severe(format("Error during vault discovery cycle: %s", e.getMessage()));
            return List.of();
        }
    }
    
    /**
     * Gets all current EDR keys from vault for cleanup purposes
     */
    public List<String> getCurrentEdrKeys() {
        try {
            return getEdrKeys();
        } catch (Exception e) {
            LOGGER.warning(format("Error getting current EDR keys: %s", e.getMessage()));
            return List.of();
        }
    }
    
    /**
     * Checks if an EDR has already been processed (has deployment metadata)
     */
    public boolean isEdrAlreadyProcessed(String edrKey) {
        try {
            // Use direct HTTP GET to read metadata since bettercloud client doesn't support it properly
            String apiUrl = vaultAddress + getMetadataApiPath(edrKey);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Vault-Token", vaultToken);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                LOGGER.warning(format("Could not read metadata for EDR %s: HTTP %d", edrKey, responseCode));
                conn.disconnect();
                return false;
            }
            
            // Read response
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();
            
            String responseJson = response.toString();
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode dataNode = root.path("data");
            JsonNode customMetadataNode = dataNode.path("custom_metadata");
            
            if (!customMetadataNode.isMissingNode() && !customMetadataNode.isNull()) {
                // Check if EDR has been analyzed (has processed_by field)
                String processedBy = customMetadataNode.path("processed_by").asText("");
                boolean hasBeenAnalyzed = !processedBy.isEmpty();
                
                if (hasBeenAnalyzed) {
                    String isKafkaFlag = customMetadataNode.path("is_kafka").asText("false");
                    String kafkaReadyFlag = customMetadataNode.path("kafka_ready_for_deployment").asText("false");
                    LOGGER.fine(format("EDR %s already analyzed: is_kafka=%s, kafka_ready=%s", 
                            edrKey, isKafkaFlag, kafkaReadyFlag));
                    return true;
                }
            } else {
                LOGGER.fine(format("EDR %s has no custom_metadata, needs processing", edrKey));
            }
            
            return false;
            
        } catch (Exception e) {
            LOGGER.warning(format("Could not check processed status for EDR %s: %s", edrKey, e.getMessage()));
            e.printStackTrace();
            return false;
        }
    }
    
    private EdrDiscoveryResult reconstructResultFromMetadata(String edrKey) {
        try {
            // Use direct HTTP GET to read metadata
            String apiUrl = vaultAddress + getMetadataApiPath(edrKey);
            
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Vault-Token", vaultToken);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                LOGGER.warning(format("Could not read metadata for reconstruction of %s: HTTP %d", edrKey, responseCode));
                conn.disconnect();
                return null;
            }
            
            // Read response
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();
            
            // Parse JSON response
            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode customMetadataNode = root.path("data").path("custom_metadata");
            
            if (customMetadataNode.isMissingNode() || customMetadataNode.isNull()) {
                LOGGER.warning(format("No custom_metadata found for %s during reconstruction", edrKey));
                return null;
            }
            
            // Extract metadata fields
            boolean isKafka = "true".equals(customMetadataNode.path("is_kafka").asText("false"));
            boolean kafkaReady = "true".equals(customMetadataNode.path("kafka_ready_for_deployment").asText("false"));
            boolean needsTls = "true".equals(customMetadataNode.path("needs_tls").asText("false"));
            
            LOGGER.info(format("Reconstructed result for %s: isKafka=%s, ready=%s, TLS=%s", 
                    edrKey, isKafka, kafkaReady, needsTls));
            
            // For non-Kafka or invalid Kafka EDRs, return result without properties
            if (!isKafka || !kafkaReady) {
                return new EdrDiscoveryResult(edrKey, isKafka, kafkaReady, needsTls, null);
            }
            
            // For valid Kafka EDRs, we need to read the actual properties from the secret
            // (we can't store all Kafka properties in custom_metadata due to size limits)
            LOGGER.info(format("EDR %s is valid Kafka, need to read full properties", edrKey));
            KafkaProperties kafkaProps = extractKafkaProperties(edrKey);
            return new EdrDiscoveryResult(edrKey, true, true, needsTls, kafkaProps);
            
        } catch (Exception e) {
            LOGGER.warning(format("Failed to reconstruct result from metadata for %s: %s", edrKey, e.getMessage()));
            return null;
        }
    }
    
    private boolean checkVaultConnection() {
        try {
            LOGGER.fine("Checking vault connection...");
            vault.auth().lookupSelf();
            LOGGER.fine("Vault connection successful");
            return true;
        } catch (VaultException e) {
            LOGGER.severe(format("Vault connection check failed: %s", e.getMessage()));
            if (e.getHttpStatusCode() == 403) {
                LOGGER.severe("HTTP 403 - Permission denied. Check vault token and policies.");
            } else if (e.getHttpStatusCode() == 404) {
                LOGGER.severe("HTTP 404 - Vault endpoint not found. Check vault address.");
            }
            return false;
        }
    }
    
    private List<String> getEdrKeys() {
        try {
            String listPath = getSecretListPath();
            LOGGER.info(format("Fetching EDR keys from vault at path: %s", listPath));
            
            // List all keys in the secret/ path (with folder support)
            LogicalResponse response = vault.logical().list(listPath);
            Map<String, String> secretList = response != null ? response.getData() : null;
            
            if (secretList == null) {
                LOGGER.fine("No vault response data received");
                return List.of();
            }
            
            if (!secretList.containsKey("keys")) {
                LOGGER.fine("No 'keys' field in vault response");
                return List.of();
            }
            
            String keysString = secretList.get("keys");
            
            if (keysString == null || keysString.trim().isEmpty()) {
                return List.of();
            }
            
            List<String> edrKeys = new ArrayList<>();
            
            // Handle both comma-separated and JSON array formats
            if (keysString.trim().startsWith("[")) {
                // JSON array format - parse as JSON
                try {
                    JsonNode keysArray = objectMapper.readTree(keysString);
                    if (keysArray.isArray()) {
                        for (JsonNode keyNode : keysArray) {
                            String key = keyNode.asText().trim();
                            if (key.startsWith(EDR_PREFIX)) {
                                edrKeys.add(key);
                            }
                        }
                    }
                } catch (JsonProcessingException e) {
                    LOGGER.warning(format("Failed to parse keys as JSON: %s", e.getMessage()));
                }
            } else {
                // Comma-separated format
                String[] allKeys = keysString.split(",");
                for (String key : allKeys) {
                    String trimmedKey = key.trim();
                    if (trimmedKey.startsWith(EDR_PREFIX)) {
                        edrKeys.add(trimmedKey);
                    }
                }
            }
            
            LOGGER.info(format("Found %d EDR keys: %s", edrKeys.size(), edrKeys));
            return edrKeys;
            
        } catch (VaultException e) {
            LOGGER.severe(format("Failed to list vault keys: %s", e.getMessage()));
            return List.of();
        }
    }
    
    private EdrDiscoveryResult processEdrKey(String edrKey) {
        LOGGER.info(format("Processing EDR: %s", edrKey));
        
        try {
            // Check if already processed
            if (isEdrAlreadyProcessed(edrKey)) {
                LOGGER.info(format("EDR %s already processed, checking cache", edrKey));
                EdrDiscoveryResult cached = discoveryCache.get(edrKey);
                if (cached != null) {
                    return cached;
                }
                
                // Cache miss - reconstruct from metadata
                LOGGER.info(format("Cache miss for %s, reconstructing from metadata", edrKey));
                EdrDiscoveryResult result = reconstructResultFromMetadata(edrKey);
                if (result != null) {
                    discoveryCache.put(edrKey, result);
                    return result;
                }
                // If reconstruction fails, fall through to full processing
                LOGGER.warning(format("Failed to reconstruct %s from metadata, will reprocess", edrKey));
            }
            
            // Check if this is a Kafka EDR
            boolean isKafka = isKafkaEdr(edrKey);
            if (!isKafka) {
                LOGGER.fine(format("EDR %s is not Kafka type, tagging as non-Kafka", edrKey));
                tagEdrForDeployment(edrKey, false, false);
                return new EdrDiscoveryResult(edrKey, false, false, false, null);
            }
            
            LOGGER.info(format("EDR %s is Kafka, extracting properties...", edrKey));
            
            // Extract and validate Kafka properties
            KafkaProperties kafkaProps = extractKafkaProperties(edrKey);
            boolean hasRequiredProps = kafkaProps.hasRequiredProperties();
            boolean needsTls = kafkaProps.needsTls();
            
            if (hasRequiredProps) {
                LOGGER.info(format("EDR %s has valid Kafka properties, tagging for deployment (TLS: %s)", edrKey, needsTls));
                tagEdrForDeployment(edrKey, true, needsTls);
                return new EdrDiscoveryResult(edrKey, true, true, needsTls, kafkaProps);
            } else {
                LOGGER.warning(format("EDR %s missing required Kafka properties, tagging as invalid", edrKey));
                tagEdrForDeployment(edrKey, false, false);
                return new EdrDiscoveryResult(edrKey, true, false, false, null);
            }
            
        } catch (Exception e) {
            LOGGER.severe(format("Error processing EDR %s: %s", edrKey, e.getMessage()));
            return new EdrDiscoveryResult(edrKey, false, false, false, null);
        }
    }
    
    private boolean isKafkaEdr(String edrKey) {
        try {
            LOGGER.fine(format("Checking if %s is Kafka EDR...", edrKey));
            
            String secretPath = getSecretPath(edrKey);
            LogicalResponse response = vault.logical().read(secretPath);
            Map<String, String> secretData = response != null ? response.getData() : null;
            
            if (secretData == null || secretData.isEmpty()) {
                LOGGER.warning(format("No secret data found for EDR key: %s", edrKey));
                return false;
            }
            
            String contentJson = secretData.get("content");
            if (contentJson == null) {
                LOGGER.warning(format("No content field found in secret for EDR key: %s", edrKey));
                return false;
            }
            
            JsonNode contentNode = objectMapper.readTree(contentJson);
            JsonNode properties = contentNode.get("properties");
            
            if (properties != null) {
                String edrType = properties.path("https://w3id.org/edc/v0.0.1/ns/type").asText("");
                // Case-insensitive comparison to accept both "Kafka" and "kafka"
                boolean isKafka = KAFKA_TYPE.equalsIgnoreCase(edrType);
                LOGGER.info(format("EDR %s type: %s (is_kafka: %s)", edrKey, edrType, isKafka));
                return isKafka;
            }
            
            return false;
            
        } catch (VaultException | JsonProcessingException e) {
            LOGGER.severe(format("Error checking EDR type for %s: %s", edrKey, e.getMessage()));
            return false;
        }
    }
    
    private KafkaProperties extractKafkaProperties(String edrKey) {
        try {
            String secretPath = getSecretPath(edrKey);
            Map<String, String> secretData = vault.logical().read(secretPath).getData();
            
            if (secretData == null) {
                LOGGER.warning(format("No secret data found for EDR %s at path %s", edrKey, secretPath));
                return new KafkaProperties();
            }
            
            String contentJson = secretData.get("content");
            if (contentJson == null) {
                LOGGER.warning(format("No content field found for EDR %s", edrKey));
                return new KafkaProperties();
            }
            
            JsonNode contentNode = objectMapper.readTree(contentJson);
            
            // Extract from both EDC properties and DataAddress properties
            JsonNode properties = contentNode.get("properties");
            JsonNode dataAddressProps = contentNode.at("/dataAddress/properties");
            
            String bootstrapServers = getPropertyValue(properties, dataAddressProps,
                    "https://w3id.org/edc/v0.0.1/ns/kafka.bootstrap.servers", "kafka.bootstrap.servers");
            String topic = getPropertyValue(properties, dataAddressProps,
                    "https://w3id.org/edc/v0.0.1/ns/topic", "topic");
            String securityProtocol = getPropertyValue(properties, dataAddressProps,
                    "https://w3id.org/edc/v0.0.1/ns/security.protocol", "security.protocol");
            String saslMechanism = getPropertyValue(properties, dataAddressProps,
                    "https://w3id.org/edc/v0.0.1/ns/sasl.mechanism", "sasl.mechanism");
            String saslJaasConfig = getPropertyValue(properties, dataAddressProps,
                    "https://w3id.org/edc/v0.0.1/ns/sasl.jaas.config", "sasl.jaas.config");
            
            // Set defaults if not provided
            if (securityProtocol == null || securityProtocol.isEmpty()) {
                securityProtocol = "PLAINTEXT";
            }
            if (saslMechanism == null || saslMechanism.isEmpty()) {
                saslMechanism = "PLAIN";
            }
            
            boolean needsTls = "SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol);
            boolean needsSasl = "PLAIN".equals(saslMechanism) || 
                              "SASL_PLAINTEXT".equals(securityProtocol) || 
                              "SASL_SSL".equals(securityProtocol);
            
            LOGGER.info(format("EDR %s - Security Protocol: %s, Needs TLS: %s, Needs SASL: %s", 
                    edrKey, securityProtocol, needsTls, needsSasl));
            LOGGER.info(format("EDR %s - Bootstrap: %s, Topic: %s", edrKey, bootstrapServers, topic));
            
            return new KafkaProperties(bootstrapServers, topic, securityProtocol, saslMechanism, 
                                     saslJaasConfig, needsTls, needsSasl);
            
        } catch (VaultException | JsonProcessingException e) {
            LOGGER.severe(format("Error extracting properties for %s: %s", edrKey, e.getMessage()));
            return new KafkaProperties();
        }
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
    
    private void tagEdrForDeployment(String edrKey, boolean isKafka, boolean needsTls) {
        try {
            String timestamp = Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_INSTANT);
            
            // Build the custom_metadata map with string values only (Vault KV v2 requirement)
            Map<String, String> customMetadataFields = new HashMap<>();
            customMetadataFields.put("is_kafka", String.valueOf(isKafka));
            customMetadataFields.put("kafka_ready_for_deployment", String.valueOf(isKafka));
            customMetadataFields.put("needs_tls", String.valueOf(needsTls));
            customMetadataFields.put("vault_checked_at", timestamp);
            customMetadataFields.put("processed_by", "edr-kafka-checker-java");
            
            // Wrap in the required structure as per Vault API:
            // POST to /v1/secret/metadata/<key> with:
            // { "custom_metadata": { "key1": "value1", "key2": "value2" } }
            Map<String, Object> payload = new HashMap<>();
            payload.put("custom_metadata", customMetadataFields);
            
            // Use direct HTTP POST since bettercloud client doesn't properly support custom_metadata
            String apiUrl = vaultAddress + getMetadataApiPath(edrKey);
            String jsonPayload = objectMapper.writeValueAsString(payload);
            
            LOGGER.fine(format("POSTing custom_metadata to %s: %s", apiUrl, jsonPayload));
            
            // Make HTTP POST request
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Vault-Token", vaultToken);
            conn.setDoOutput(true);
            
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                LOGGER.info(format("Successfully tagged EDR %s with is_kafka=%s, needs_tls=%s (HTTP %d)", 
                        edrKey, isKafka, needsTls, responseCode));
            } else {
                LOGGER.severe(format("Failed to tag EDR %s: HTTP %d", edrKey, responseCode));
            }
            
            conn.disconnect();
            
        } catch (IOException e) {
            LOGGER.severe(format("Failed to tag EDR %s: %s", edrKey, e.getMessage()));
        }
    }
    
    /**
     * Clears the discovery cache
     */
    public void clearCache() {
        discoveryCache.clear();
        LOGGER.info("Discovery cache cleared");
    }
    
    /**
     * Helper class to hold extracted Kafka properties
     */
    public static class KafkaProperties {
        private final String bootstrapServers;
        private final String topic;
        private final String securityProtocol;
        private final String saslMechanism;
        private final String saslJaasConfig;
        private final boolean needsTls;
        private final boolean needsSasl;
        
        public KafkaProperties() {
            this("", "", "PLAINTEXT", "PLAIN", "", false, false);
        }
        
        public KafkaProperties(String bootstrapServers, String topic, String securityProtocol, 
                             String saslMechanism, String saslJaasConfig, boolean needsTls, boolean needsSasl) {
            this.bootstrapServers = bootstrapServers;
            this.topic = topic;
            this.securityProtocol = securityProtocol;
            this.saslMechanism = saslMechanism;
            this.saslJaasConfig = saslJaasConfig;
            this.needsTls = needsTls;
            this.needsSasl = needsSasl;
        }
        
        public boolean hasRequiredProperties() {
            return bootstrapServers != null && !bootstrapServers.isEmpty() &&
                    topic != null && !topic.isEmpty();
        }
        
        // Getters
        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public String getTopic() {
            return topic;
        }

        public String getSecurityProtocol() {
            return securityProtocol;
        }

        public String getSaslMechanism() {
            return saslMechanism;
        }

        public String getSaslJaasConfig() {
            return saslJaasConfig;
        }

        public boolean needsTls() {
            return needsTls;
        }

        public boolean needsSasl() {
            return needsSasl;
        }
    }
    
    /**
     * Gets the creation timestamp of an EDR from vault metadata.
     * Returns the created_time field from vault's metadata response.
     * Returns 0 if timestamp cannot be retrieved.
     */
    public long getEdrCreationTimestamp(String edrKey) {
        try {
            // Use direct HTTP GET to read metadata
            String apiUrl = vaultAddress + getMetadataApiPath(edrKey);
            
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Vault-Token", vaultToken);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                LOGGER.warning(format("Could not read metadata for EDR %s: HTTP %d", edrKey, responseCode));
                conn.disconnect();
                return 0;
            }
            
            // Read response
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();
            
            // Parse JSON response
            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode dataNode = root.path("data");
            
            // Get created_time from metadata (format: "2024-11-09T10:15:30.123456Z")
            String createdTime = dataNode.path("created_time").asText("");
            
            if (!createdTime.isEmpty()) {
                // Parse ISO 8601 timestamp to epoch milliseconds
                try {
                    // Remove the 'Z' and parse as ISO instant
                    java.time.Instant instant = java.time.Instant.parse(createdTime);
                    long timestamp = instant.toEpochMilli();
                    LOGGER.fine(format("EDR %s created at: %s (epoch: %d)", edrKey, createdTime, timestamp));
                    return timestamp;
                } catch (Exception e) {
                    LOGGER.warning(format("Failed to parse created_time for EDR %s: %s", edrKey, e.getMessage()));
                    return 0;
                }
            }
            
            LOGGER.fine(format("No created_time found for EDR %s", edrKey));
            return 0;
            
        } catch (Exception e) {
            LOGGER.warning(format("Error getting creation timestamp for EDR %s: %s", edrKey, e.getMessage()));
            return 0;
        }
    }
}
package org.eclipse.dse.core.kafkaproxy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaProxyKubernetesExtensionTest {

    private final KafkaProxyKubernetesExtension extension = new KafkaProxyKubernetesExtension();

    @Test
    void parseAnnotations_withYamlStyleAndQuotes() {
        String config = "loadbalancer.openstack.org/default-tls-container-ref: \"https://key-manager.example.com/v1/secrets/00000000-0000-0000-0000-000000000000\"";
        Map<String, String> result = extension.parseAnnotations(config);
        
        assertTrue(result.containsKey("loadbalancer.openstack.org/default-tls-container-ref"));
        // Quotes should be stripped
        assertEquals("https://key-manager.example.com/v1/secrets/00000000-0000-0000-0000-000000000000", 
                result.get("loadbalancer.openstack.org/default-tls-container-ref"));
    }

    @Test
    void parseAnnotations_withQuotesInKey() {
        String config = "\"loadbalancer.openstack.org/default-tls-container-ref\": \"https://some-url\"";
        Map<String, String> result = extension.parseAnnotations(config);
        
        // Key should NOT contain quotes
        assertFalse(result.containsKey("\"loadbalancer.openstack.org/default-tls-container-ref\""));
        assertTrue(result.containsKey("loadbalancer.openstack.org/default-tls-container-ref"));
        assertEquals("https://some-url", result.get("loadbalancer.openstack.org/default-tls-container-ref"));
    }

    @Test
    void parseAnnotations_withMultipleLinesAndQuotes() {
        String config = "service.beta.kubernetes.io/aws-load-balancer-type: nlb\n" +
                        "\"service.beta.kubernetes.io/aws-load-balancer-internal\": \"true\"";
        Map<String, String> result = extension.parseAnnotations(config);
        
        assertEquals("nlb", result.get("service.beta.kubernetes.io/aws-load-balancer-type"));
        assertTrue(result.containsKey("service.beta.kubernetes.io/aws-load-balancer-internal"));
        assertEquals("true", result.get("service.beta.kubernetes.io/aws-load-balancer-internal"));
    }
    
    @Test
    void parseAnnotations_withSingleQuotes() {
        String config = "key: 'value'";
        Map<String, String> result = extension.parseAnnotations(config);
        assertEquals("value", result.get("key"));
    }

    @Test
    void parseAnnotations_withQuotedLine() {
        // Helm or other config systems might wrap the whole line in quotes if they see colons
        String config = "\"loadbalancer.openstack.org/default-tls-container-ref: https://some-url\"";
        Map<String, String> result = extension.parseAnnotations(config);
        
        // This fails with the current implementation because stripQuotes on '"key' returns '"key'
        assertTrue(result.containsKey("loadbalancer.openstack.org/default-tls-container-ref"), 
                "Key should be found without leading quote. Found keys: " + result.keySet());
        assertEquals("https://some-url", result.get("loadbalancer.openstack.org/default-tls-container-ref"));
    }

    @Test
    void parsePodLabels_withQuotes() {
        String config = "app=\"my-app\",env='prod'";
        Map<String, String> result = extension.parsePodLabels(config);
        assertEquals("my-app", result.get("app"));
        assertEquals("prod", result.get("env"));
    }
}

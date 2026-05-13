package org.eclipse.dse.core.kafkaproxy.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.eclipse.dse.core.kafkaproxy.model.EdrProperties;
import org.eclipse.dse.core.kafkaproxy.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KubernetesDeployerServiceTest {

    private KubernetesDeployerService deployerService;
    private KubernetesClient kubernetesClient;
    private VaultService vaultService;

    @BeforeEach
    void setUp() {
        kubernetesClient = mock(KubernetesClient.class);
        vaultService = mock(VaultService.class);

        deployerService = new KubernetesDeployerService(
                kubernetesClient, "ns", "image", vaultService, "participant", "1.2.3.4", 9092,
                false, null, null, null, null, null, false, null, null, null, null, 10, "ClusterIP", null, null, null
        );
    }

    void invokeCreateOrUpdateTlsCaConfigMap(String cert) {
        EdrProperties properties = new EdrProperties(null, null, null, null, null, null, null, cert, null, null, null, null);
        deployerService.createOrUpdateTlsCaConfigMap(
                new Resource("edr1", "proxy1", properties, null));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_validPemCert_clientThrowsNpe() throws Exception {
        // Valid certificate (Base64 for "Hello World" is SGVsbG8gV29ybGQ=)
        String validCert = "-----BEGIN CERTIFICATE-----\nSGVsbG8gV29ybGQ=\n-----END CERTIFICATE-----";
        assertThrows(NullPointerException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(validCert));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_missingBeginDelimiter_throwsIAE() {
        String noBegin = "SGVsbG8gV29ybGQ=\n-----END CERTIFICATE-----";
        Exception ex = assertThrows(IllegalArgumentException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(noBegin));
        assertTrue(ex.getMessage().contains("n'a pas les délimiteurs PEM valides"));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_missingEndDelimiter_throwsIAE() {
        String noEnd = "-----BEGIN CERTIFICATE-----\nSGVsbG8gV29ybGQ=";
        Exception ex = assertThrows(IllegalArgumentException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(noEnd));
        assertTrue(ex.getMessage().contains("n'a pas les délimiteurs PEM valides"));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_emptyContent_throwsIAE() {
        String emptyContent = "-----BEGIN CERTIFICATE-----\n\n-----END CERTIFICATE-----";
        Exception ex = assertThrows(IllegalArgumentException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(emptyContent));
        assertTrue(ex.getMessage().contains("Le contenu du certificat est vide"));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_invalidBase64_throwsIAE() {
        String badBase64 = "-----BEGIN CERTIFICATE-----\n!!!NotBase64!!!\n-----END CERTIFICATE-----";
        Exception ex = assertThrows(IllegalArgumentException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(badBase64));
        assertTrue(ex.getMessage().contains("pas au format Base64 valide"));
    }

    @Test
    void createOrUpdateTlsCaConfigMap_nullCert_throwsIAE() {
        Exception ex = assertThrows(IllegalArgumentException.class, () -> invokeCreateOrUpdateTlsCaConfigMap(null));
        assertTrue(ex.getMessage().contains("est vide"));
    }

    @Test
    void generateTlsCaConfigMapName_simpleEdrName_returnsCorrectFormat() throws Exception {
        assertEquals("kp-participant-my-edr-ca", deployerService.generateTlsCaConfigMapName("my-edr"));
    }

    @Test
    void generateTlsCaConfigMapName_edrWithPrefix_returnsCorrectFormat() throws Exception {
        assertEquals("kp-participant-my-edr-ca", deployerService.generateTlsCaConfigMapName("edr--my-edr"));
    }

    @Test
    void generateTlsCaConfigMapName_edrWithUnderscores_returnsCorrectFormat() throws Exception {
        assertEquals("kp-participant-my-edr-ca", deployerService.generateTlsCaConfigMapName("my_edr"));
    }

    @Test
    void generateTlsCaConfigMapName_longEdrName_truncatesTo63Chars() throws Exception {
        String longEdr = "this-is-a-very-very-very-very-very-very-very-very-long-edr-key";
        String result = deployerService.generateTlsCaConfigMapName(longEdr);
        assertTrue(result.length() <= 63);
        assertTrue(result.startsWith("kp-participant-"));
        assertTrue(result.endsWith("-ca"));
    }
}

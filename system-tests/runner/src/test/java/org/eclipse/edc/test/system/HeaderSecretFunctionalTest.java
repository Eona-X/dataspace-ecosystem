package org.eclipse.edc.test.system;

import org.eclipse.edc.connector.dataplane.spi.response.TransferErrorResponse;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ad hoc functional coverage for ticket #385 (HeaderSecretParamsDecorator), exercised against the
 * already-onboarded live local dataspace. Mirrors the scenarios covered by
 * HeaderSecretParamsDecoratorTest (unit, mocked Vault) but through a real transfer against the real
 * Vault and the real running data-plane, via the /headers echo endpoint on provider-backend.
 */
@EndToEndTest
class HeaderSecretFunctionalTest extends AbstractEndToEndTests {

    private static final LocalProvider PROVIDER = new LocalProvider();
    private static final LocalConsumer CONSUMER = new LocalConsumer();

    private static final String SECRET_A = "header-secret-385-alias-a";
    private static final String SECRET_B = "header-secret-385-alias-b";
    private static final String VALUE_A = "ticket-385-value-a";
    private static final String VALUE_B = "ticket-385-value-b";

    private static final String ASSET_SINGLE = "header-secret-single";
    private static final String ASSET_MULTI = "header-secret-multi";
    private static final String ASSET_NONE = "header-secret-none";
    private static final String ASSET_MISSING_SINGLE = "header-secret-missing-single";
    private static final String ASSET_MISSING_MULTI = "header-secret-missing-multi";
    private static final String ASSET_MIXED = "header-secret-mixed";

    @BeforeAll
    static void setup() {
        PROVIDER.createSecret(SECRET_A, VALUE_A);
        PROVIDER.createSecret(SECRET_B, VALUE_B);

        PROVIDER.createEntry(ASSET_SINGLE, "Header secret - single", "one header-secret property, resolves", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers",
                "header-secret:X-Ticket-385-A", SECRET_A
        ));

        PROVIDER.createEntry(ASSET_MULTI, "Header secret - multi", "two header-secret properties, both resolve", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers",
                "header-secret:X-Ticket-385-A", SECRET_A,
                "header-secret:X-Ticket-385-B", SECRET_B
        ));

        PROVIDER.createEntry(ASSET_NONE, "Header secret - none", "no header-secret property, unaffected", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers"
        ));

        PROVIDER.createEntry(ASSET_MISSING_SINGLE, "Header secret - missing single", "one header-secret property, alias missing from vault", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers",
                "header-secret:X-Ticket-385-Missing", "header-secret-385-alias-does-not-exist"
        ));

        PROVIDER.createEntry(ASSET_MISSING_MULTI, "Header secret - missing multi", "two header-secret properties, both aliases missing", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers",
                "header-secret:X-Ticket-385-Missing-1", "header-secret-385-alias-missing-1",
                "header-secret:X-Ticket-385-Missing-2", "header-secret-385-alias-missing-2"
        ));

        PROVIDER.createEntry(ASSET_MIXED, "Header secret - mixed", "one valid + one missing header-secret property: must fail entirely, no partial header", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/headers",
                "header-secret:X-Ticket-385-A", SECRET_A,
                "header-secret:X-Ticket-385-Missing", "header-secret-385-alias-does-not-exist"
        ));
    }

    @Test
    void singleHeaderSecret_resolves() {
        var contractId = negotiateAndGetContractId(ASSET_SINGLE);
        await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> data = CONSUMER.queryData(contractId, Map.of(), 200, Map.class);
            assertThat(data).containsEntry("X-Ticket-385-A", VALUE_A);
        });
    }

    @Test
    void multipleHeaderSecrets_allResolve() {
        var contractId = negotiateAndGetContractId(ASSET_MULTI);
        await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> data = CONSUMER.queryData(contractId, Map.of(), 200, Map.class);
            assertThat(data).containsEntry("X-Ticket-385-A", VALUE_A);
            assertThat(data).containsEntry("X-Ticket-385-B", VALUE_B);
        });
    }

    @Test
    void noHeaderSecretProperty_unaffected() {
        var contractId = negotiateAndGetContractId(ASSET_NONE);
        await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> data = CONSUMER.queryData(contractId, Map.of(), 200, Map.class);
            assertThat(data).doesNotContainKeys("X-Ticket-385-A", "X-Ticket-385-B");
        });
    }

    @Test
    void singleMissingAlias_transferFails() {
        var contractId = negotiateAndGetContractId(ASSET_MISSING_SINGLE);
        await().atMost(TEST_TIMEOUT).untilAsserted(() ->
                CONSUMER.queryData(contractId, Map.of(), 500, TransferErrorResponse.class));
    }

    @Test
    void multipleMissingAliases_transferFails() {
        var contractId = negotiateAndGetContractId(ASSET_MISSING_MULTI);
        await().atMost(TEST_TIMEOUT).untilAsserted(() ->
                CONSUMER.queryData(contractId, Map.of(), 500, TransferErrorResponse.class));
    }

    @Test
    void mixedValidAndMissingAlias_failsEntirely_noPartialHeaders() {
        var contractId = negotiateAndGetContractId(ASSET_MIXED);
        await().atMost(TEST_TIMEOUT).untilAsserted(() ->
                CONSUMER.queryData(contractId, Map.of(), 500, TransferErrorResponse.class));
    }

    private String negotiateAndGetContractId(String assetId) {
        var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, assetId);
        return CONSUMER.participantClient().baseManagementRequest()
                .get("/v3/transferprocesses/" + transferProcessId)
                .then().statusCode(200).extract().body().jsonPath().getString("contractId");
    }
}

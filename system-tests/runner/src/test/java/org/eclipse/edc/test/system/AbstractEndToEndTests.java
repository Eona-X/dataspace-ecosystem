package org.eclipse.edc.test.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.monitor.Monitor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcessStates.STARTED;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.VALUE;
import static org.eclipse.edc.jsonld.spi.Namespaces.DSPACE_SCHEMA;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.DCAT_DATASET_ATTRIBUTE;

public class AbstractEndToEndTests {

    protected static final ObjectMapper MAPPER = JacksonJsonLd.createObjectMapper();
    protected static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Monitor MONITOR = new ConsoleMonitor();
    private static final JsonLd JSON_LD = new TitaniumJsonLd(MONITOR);
    private static final Duration TEST_POLL_INTERVAL = Duration.ofMillis(500);
    public static final String HTTP_DATA_PULL = "HttpData-PULL";

    protected List<JsonObject> queryParticipantDatasets(AbstractAuthority authority, String participantDid, String catalogUrl) {
        AtomicReference<List<JsonObject>> datasets = new AtomicReference<>();
        await().atMost(TEST_TIMEOUT)
                .pollInterval(TEST_POLL_INTERVAL)
                .untilAsserted(() -> {
                    var catalog = authority.queryCatalog(MAPPER, JSON_LD, catalogUrl).stream()
                            .filter(isCatalogOf(participantDid))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Failed to find Catalog for participant %s".formatted(participantDid)));
                    datasets.set(Optional.ofNullable(catalog.getJsonArray(DCAT_DATASET_ATTRIBUTE)).map(arr -> arr.stream().map(JsonValue::asJsonObject).toList()).orElse(List.of()));
                });

        return datasets.get();
    }

    protected String negotiationContractAndStartTransfer(AbstractParticipant consumer, AbstractParticipant provider, String assetId) {
        return negotiationContractAndStartTransfer(consumer, provider, assetId, HTTP_DATA_PULL);
    }

    protected String negotiationContractAndStartTransfer(AbstractParticipant consumer, AbstractParticipant provider, String assetId, String transferType) {
        var transferProcessId = consumer.participantClient().requestAssetFrom(assetId, provider.participantClient())
                .withTransferType(transferType)
                .execute();

        await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
            var state = consumer.participantClient().getTransferProcessState(transferProcessId);
            assertThat(state).isEqualTo(STARTED.name());
        });

        return transferProcessId;
    }

    protected static void getCredentials(AbstractEntity participant)  {
        await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
            boolean hasMembershipCredential = false;
            boolean hasDomainCredential = false;
            List<JsonObject> verifiableCredentials = participant.getCredentials(MAPPER);
            for (JsonObject verifiableCredential : verifiableCredentials) {
                JsonObject vc = verifiableCredential.getJsonObject("verifiableCredential");
                JsonObject credential = vc.getJsonObject("credential");
                JsonArray types = credential.getJsonArray("type");
                for (int j = 0; j < types.size(); j++) {
                    String type = types.getString(j);
                    if (type.equals("MembershipCredential")) {
                        hasMembershipCredential = true;
                    }
                    if (type.equals("DomainCredential")) {
                        hasDomainCredential = true;
                    }
                }
            }
            assert hasMembershipCredential : "Missing MembershipCredential type";
            assert hasDomainCredential : "Missing DomainCredential type";
        });
    }

    private static Predicate<JsonObject> isCatalogOf(String did) {
        return catalog -> catalog.getJsonArray(DSPACE_SCHEMA + "participantId")
                .stream()
                .allMatch(jsonValue -> did.equals(jsonValue.asJsonObject().getString(VALUE)));
    }


}

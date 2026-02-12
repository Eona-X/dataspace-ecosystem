package org.eclipse.edc.test.system;

import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubConsumerAsyncClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractNegotiationStates;
import org.eclipse.edc.connector.dataplane.spi.response.TransferErrorResponse;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.Key;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.dse.iam.policy.PolicyConstants.DOMAIN_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.GENERIC_CLAIM_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.edc.connector.controlplane.test.system.utils.PolicyFixtures.atomicConstraint;
import static org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcessStates.TERMINATED;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.ID;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_PREFIX;
import static org.eclipse.edc.test.system.AbstractAuthority.DOMAIN_ROUTE;
import static org.eclipse.edc.test.system.AbstractAuthority.DOMAIN_TRAVEL;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_FAILURE_REST_API;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_KAFKA_STREAM;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_20_SEC_API;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API_DOMAIN;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API_EMBEDDED_QUERY_PARAMS;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API_OAUTH2;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED;
import static org.eclipse.edc.test.system.LocalProvider.ASSET_ID_REST_API_TRAVEL_DOMAIN_RESTRICTED;
import static org.eclipse.edc.test.system.LocalProvider.EMBEDDED_QUERY_PARAM;
import static org.eclipse.edc.test.system.LocalProvider.OAUTH2_CLIENT_SECRET;
import static org.eclipse.edc.test.system.LocalProvider.OAUTH2_CLIENT_SECRET_KEY;
import static org.eclipse.edc.test.system.LocalProvider.POLICY_RESTRICTED_API;
import static org.eclipse.edc.test.system.ParticipantConstants.printConfiguration;
import static org.eclipse.edc.test.system.PostgresDataVerifier.verifyData;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EndToEndTest
public class LocalEndToEndTests extends AbstractEndToEndTests {

    public static final String KAFKA_BROKER_PULL = "KafkaBroker-PULL";
    // TEST-ONLY SECRET: This hardcoded secret is used exclusively for JWT generation in tests.
    public static final String SECRET = "a-string-secret-at-least-256-bits-long";
    public static String dummyJwt;
    public static String dummyJwtWithMultipleRoles;
    public static String dummyJwtNonExistentParticipant;
    private static final String EVENT_HUB_CONNECTION_STRING_ALIAS = "event-hub-connection-string";
    private static final String EVENT_HUB_CONNECTION_STRING_SECRET = "Endpoint=sb://eventhubs;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
    private static final String LOCAL_CONSUMER_EVENT_HUB_CONNECTION_STRING = "Endpoint=sb://localhost:52717;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
    private static final String VAULT_TOKEN = "root";
    private static final String EVENT_HUB_NAMESPACE = "local-eventhub-eventhubs";
    private static final String EVENT_HUB_NAME = "eh1";
    public static final String REPORT_HEADER_WITHOUT_COUNTERPARTY_INFO = "contract_id,counterparty_name,data_transfer_response_status," +
            "total_transfer_size_in_kB,total_number_of_events";

    private static final LocalAuthority AUTHORITY = new LocalAuthority();
    private static final LocalProvider PROVIDER = new LocalProvider();
    private static final LocalConsumer CONSUMER = new LocalConsumer();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> USED_CONTRACT_ID = new ArrayList<>();
    private static EventHubConsumerAsyncClient consumer;

    private static String kafkacatPod = null;

    public static void initializeParticipant(AbstractEntity participant) {
        AUTHORITY.createParticipant(participant.name(), participant.did());
        participant.requestCredential(AUTHORITY.did(), MEMBERSHIP_CREDENTIAL_TYPE, "membership-credential-def-1");
        participant.requestCredential(AUTHORITY.did(), DOMAIN_CREDENTIAL_TYPE, "domain-credential-def-1");
    }

    @BeforeAll
    static void beforeAll() {
        // Print test configuration for debugging
        printConfiguration();

        consumer = new EventHubClientBuilder()
                .fullyQualifiedNamespace(EVENT_HUB_NAMESPACE)
                .eventHubName(EVENT_HUB_NAME)
                .connectionString(LOCAL_CONSUMER_EVENT_HUB_CONNECTION_STRING)
                .consumerGroup("$default")
                .buildAsyncConsumerClient();

        // prepare authority
        createKey(AUTHORITY, EVENT_HUB_CONNECTION_STRING_ALIAS, EVENT_HUB_CONNECTION_STRING_SECRET);
        AUTHORITY.defineMembershipCredential();
        AUTHORITY.defineDomainCredential();

        // prepare participants
        List.of(PROVIDER, CONSUMER, AUTHORITY).forEach(LocalEndToEndTests::initializeParticipant);

        List.of(CONSUMER, PROVIDER, AUTHORITY).forEach(AbstractEndToEndTests::getCredentials);
        // seed provider data
        seedData();

        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());

        dummyJwt = Jwts.builder()
                .header()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .and()
                .claims(Map.of("roles", List.of("Participant.consumer")))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        dummyJwtWithMultipleRoles = Jwts.builder()
                .header()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .and()
                .claims(Map.of("roles", List.of("Read.consumer", "Participant.consumer")))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        dummyJwtNonExistentParticipant = Jwts.builder()
                .header()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .and()
                .claims(Map.of("roles", List.of("Participant.doesnotexist")))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

    }


    @AfterAll
    public static void afterAll() {
        consumer.receive(true)
                .takeUntil(event -> {
                    String contractId;
                    String timestamp;
                    String updatedTelemetryEvent;
                    try {
                        contractId = OBJECT_MAPPER.readTree(event.getData().getBodyAsString())
                                .path("properties").path("contractId").asText();

                        timestamp = OBJECT_MAPPER.readTree(event.getData().getBodyAsString())
                                .path("createdAt").asText();

                        // Parse the telemetryEvent JSON
                        JsonNode telemetryEventNode = OBJECT_MAPPER.readTree(event.getData().getBodyAsString())
                                .path("properties");

                        // Add the timestamp to the telemetryEvent JSON
                        if (telemetryEventNode.isObject()) {
                            ((ObjectNode) telemetryEventNode).put("timestamp", timestamp);
                        }

                        // Convert the updated telemetryEvent JSON back to a string
                        updatedTelemetryEvent = OBJECT_MAPPER.writeValueAsString(telemetryEventNode);

                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                    USED_CONTRACT_ID.remove(contractId);


                    given()
                            .baseUri("%s".formatted(AUTHORITY.telemetryUrl()))
                            .contentType(JSON)
                            .body(updatedTelemetryEvent)
                            .post()
                            .then()
                            .log().ifError()
                            .statusCode(201);

                    if (!verifyData(contractId)) {
                        System.out.println("Data verification failed for contract ID: " + contractId);
                    }

                    return USED_CONTRACT_ID.isEmpty(); // we will take msg until the list is empty
                })
                .timeout(Duration.ofMinutes(1))
                .doOnError(error -> {
                    if (!USED_CONTRACT_ID.isEmpty()) {
                        throw new RuntimeException("Timeout: usedContractId is not empty after 1 minute. Remaining elements: " + USED_CONTRACT_ID);
                    }
                })
                .blockLast(); // This will block until the last message is received
        consumer.close();
    }

    private static void seedData() {
        // basic api
        PROVIDER.createEntry(ASSET_ID_REST_API, "Test Asset REST", "a basic REST API", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "proxyQueryParams", Boolean.TRUE.toString()
        ), genericClaimConstraint(MEMBERSHIP_CREDENTIAL_TYPE, "name", "odrl:eq", "consumer"));

        // basic api
        PROVIDER.createEntry(ASSET_ID_REST_API_DOMAIN, "Test Asset REST", "a basic REST API", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "proxyQueryParams", Boolean.TRUE.toString()
        ), genericClaimConstraint(DOMAIN_CREDENTIAL_TYPE, "domain", "odrl:eq", DOMAIN_ROUTE));

        // api returning not authorized
        PROVIDER.createEntry(ASSET_ID_FAILURE_REST_API, "Failure REST API", "a REST API returning not authorized", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/failure"
        ));

        // api with embedded query params
        PROVIDER.createEntry(ASSET_ID_REST_API_EMBEDDED_QUERY_PARAMS, "Test Asset REST embedded query params", "a REST API with query params in the address", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "queryParams", "message=%s".formatted(EMBEDDED_QUERY_PARAM),
                "proxyQueryParams", Boolean.TRUE.toString()
        ));

        // oauth2 api
        PROVIDER.createSecret(OAUTH2_CLIENT_SECRET_KEY, OAUTH2_CLIENT_SECRET);
        PROVIDER.createEntry(ASSET_ID_REST_API_OAUTH2, "Test Asset REST Oauth2", "a REST API with oauth2 authorization", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/oauth2data",
                "proxyQueryParams", Boolean.TRUE.toString(),
                "oauth2:clientId", "clientId",
                "oauth2:clientSecretKey", OAUTH2_CLIENT_SECRET_KEY,
                "oauth2:tokenUrl", "http://provider-backend:8080/api/oauth2/token"
        ));

        // asset with policy on claim that is not satisfied by any member
        PROVIDER.createEntry(POLICY_RESTRICTED_API, "Restricted API", "An API with a restricted policy", Map.of(
                "type", "HttpData",
                "baseUrl", "http://example.com"
        ), genericClaimConstraint(MEMBERSHIP_CREDENTIAL_TYPE, "name", "odrl:eq", "unknown"));

        // basic api
        PROVIDER.createEntry(ASSET_ID_REST_20_SEC_API, "Test Asset REST - 10sec", "a basic REST API available for 10 seconds", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "proxyQueryParams", Boolean.TRUE.toString()
        ), atomicConstraint("inForceDate", "lteq", "contractAgreement+20s"));

        // Kafka properties
        JsonObject jsonObject = Json.createObjectBuilder()
                .add("topic", "my-topic")
                .add("kafka.bootstrap.servers", "proxy-provider-oauth2:30003")
                .add("security.protocol", "SASL_SSL")
                .add("sasl.mechanism", "OAUTHBEARER")
                .add("tls_ca_secret", "proxy-provider-tls-ca")
                .add("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required " +
                        "clientId='<your-client-id>' clientSecret='<your-client-secret>' tenantId='<your-tenant-id>' scope='<your-client-id>/.default'").build();
        PROVIDER.createEntry(ASSET_ID_KAFKA_STREAM, "Test Asset Kafka", "a basic kafka stream", Map.of(
                "type", "Kafka",
                "properties", jsonObject
        ), genericClaimConstraint(MEMBERSHIP_CREDENTIAL_TYPE, "name", "odrl:eq", "consumer"));

        JsonObject jsonObject2 = Json.createObjectBuilder()
                .add("topic", "tst-topic")
                .add("kafka.bootstrap.servers", "proxy-provider:30001")
                .add("security.protocol", "SASL_SSL")
                .add("sasl.mechanism", "PLAIN")
                .add("tls_ca_secret", "proxy-provider-tls-ca")
                .add("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                                "username='provider1' password='secret1'")
                .build();
        PROVIDER.createEntry(ASSET_ID_KAFKA_STREAM + "-tst-2", "Test Asset Kafka", "a basic kafka stream", Map.of(
                "type", "Kafka",
                "properties", jsonObject2
        ), genericClaimConstraint(MEMBERSHIP_CREDENTIAL_TYPE, "name", "odrl:eq", "consumer"));

        PROVIDER.createEntry(ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED, "Test Asset REST", "a basic REST API", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "proxyQueryParams", Boolean.TRUE.toString()
        ), restrictedDiscoveryClaimConstraint(DOMAIN_CREDENTIAL_TYPE, "domain", "odrl:eq", DOMAIN_ROUTE));

        PROVIDER.createEntry(ASSET_ID_REST_API_TRAVEL_DOMAIN_RESTRICTED, "Test Asset REST", "a basic REST API", Map.of(
                "type", "HttpData",
                "baseUrl", "http://provider-backend:8080/api/provider/data",
                "proxyQueryParams", Boolean.TRUE.toString()
        ), restrictedDiscoveryClaimConstraint(DOMAIN_CREDENTIAL_TYPE, "domain", "odrl:eq", DOMAIN_TRAVEL));
    }

    private static JsonObject genericClaimConstraint(String credentialType, String path, String operator, String rightOperand) {
        return atomicConstraint("%s:%s.$.%s.%s".formatted(DSE_POLICY_PREFIX, GENERIC_CLAIM_CONSTRAINT, credentialType, path), operator, rightOperand);
    }

    private static JsonObject restrictedDiscoveryClaimConstraint(String credentialType, String path, String operator, String rightOperand) {
        return atomicConstraint("%s:%s.$.%s.%s".formatted(DSE_POLICY_PREFIX, RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, credentialType, path), operator, rightOperand);
    }

    private static void createKey(AbstractEntity entity, String key, String value) {
        var body = Map.of(
                "data", Map.of(
                        "content", value
                )
        );

        given()
                .baseUri("%s/v1/secret/data/%s".formatted(entity.vaultUrl(), key))
                .contentType(ContentType.JSON)
                .header("X-Vault-Token", VAULT_TOKEN)
                .body(body)
                .post()
                .then()
                .log().ifError()
                .statusCode(200);
    }

    @Nested
    class CatalogTest {

        @Test
        void catalog_provider() {
            var assets = Set.of(
                    ASSET_ID_REST_API,
                    ASSET_ID_REST_API_DOMAIN,
                    ASSET_ID_REST_API_EMBEDDED_QUERY_PARAMS,
                    ASSET_ID_REST_API_OAUTH2,
                    POLICY_RESTRICTED_API,
                    ASSET_ID_REST_20_SEC_API,
                    ASSET_ID_FAILURE_REST_API,
                    ASSET_ID_KAFKA_STREAM,
                    ASSET_ID_KAFKA_STREAM + "-tst-2",
                    ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED,
                    ASSET_ID_REST_API_TRAVEL_DOMAIN_RESTRICTED
            );

            assertThat(queryParticipantDatasets(AUTHORITY, PROVIDER.did(), PROVIDER.controlPlaneCatalogFilterUrl()))
                    .allSatisfy(dataset -> assertThat(assets).contains(dataset.getString(ID)))
                    .allSatisfy(dataset -> assertThat(dataset.get(EDC_NAMESPACE + "createdAt")).isNotNull());
        }

        @Test
        void catalog_consumer() {
            assertThat(queryParticipantDatasets(AUTHORITY, CONSUMER.did(), CONSUMER.controlPlaneCatalogFilterUrl())).isEmpty();
        }

        @Test
        void catalog_consumer_restricted() {
            assertThat(queryParticipantDatasets(AUTHORITY, PROVIDER.did(), CONSUMER.controlPlaneCatalogFilterUrl()))
                    .anyMatch(dataset -> ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED.equals(dataset.getString(ID)))
                    .noneMatch(dataset -> ASSET_ID_REST_API_TRAVEL_DOMAIN_RESTRICTED.equals(dataset.getString(ID)));
        }
    }

    @Nested
    class TransferTest {

        @ParameterizedTest
        @ArgumentsSource(TransferTestProvider.class)
        void transfer_success(Map<String, String> queryParams, String assetId, String expected) {
            var expectedMsg = Map.of("message", expected);
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, assetId);

            // query by contract id
            var contractId = getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            USED_CONTRACT_ID.add(contractId);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var data = CONSUMER.queryData(contractId, queryParams, 200, Map.class);
                assertThat(data).isEqualTo(expectedMsg);
            });
        }

        public static String deriveStandardizedServiceName() {
            return "kp-consumer-service";
        }

        @Test
        void transfer_kafka_stream_oauth() {
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, ASSET_ID_KAFKA_STREAM, KAFKA_BROKER_PULL);
            getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            CONSUMER.finishDataTransfer(transferProcessId);
        }

        private void publishMessagesToKafka() {
            try {
                kafkacatPod = discoverPodName("", "kafkacat-");
                KafkaIntermediary.provider_publish(kafkacatPod);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Test
        void transfer_kafka_stream() throws Exception {
            var transferProcessId = negotiationContractAndStartTransfer(
                    CONSUMER, PROVIDER, ASSET_ID_KAFKA_STREAM + "-tst-2", KAFKA_BROKER_PULL
            );


            String serviceName = deriveStandardizedServiceName();
            int servicePort = 30001;  // Fixed port for the standardized service
            String topic = "tst-topic";
            String expectedMessage = "Hello from provider!";

            getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            // Wait longer to ensure proxy is deployed and ready (discovery + deployment time)
            Thread.sleep(15000);

            // Publish message on provider side
            publishMessagesToKafka();

            boolean messageReceived = KafkaIntermediary.waitForKafkaMessage(
                    serviceName, servicePort, topic, expectedMessage, Duration.ofSeconds(20), kafkacatPod
            );
            CONSUMER.finishDataTransfer(transferProcessId);
            assertTrue(messageReceived,
                    () -> "Expected message not found in Kafka topic within timeout: " + expectedMessage);
        }

        private static String discoverPodName(String transferId, String filter)
                throws IOException, InterruptedException {
            long start = System.currentTimeMillis();
            String podName = null;
            while (System.currentTimeMillis() - start < 30 * 1000L) {
                Process process = new ProcessBuilder(
                        "kubectl",
                        "--context", ParticipantConstants.KUBECTL_CONTEXT,
                        "get", "pods",
                        "-n", "default",
                        "-o", "jsonpath={.items[*].metadata.name}"
                ).start();
                process.waitFor();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String output = reader.lines().collect(Collectors.joining(" ")).trim();
                    if (!output.isEmpty()) {
                        podName = Arrays.stream(output.split("\\s+"))
                                .filter(name -> name.contains(filter + transferId))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (podName != null) {
                    return podName;
                }
                Thread.sleep(1000);
            }
            throw new RuntimeException("Timed out waiting for consumer pod for transferId " + transferId);
        }

        private static int getPodPort(String podName) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(
                    "kubectl", "get", "pod", podName,
                    "-o", "jsonpath={.spec.containers[0].ports[0].containerPort}"
            ).start();
            process.waitFor();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return Integer.parseInt(reader.readLine().trim());
            }
        }

        @Test
        void transfer_failure() {
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, ASSET_ID_FAILURE_REST_API);

            var contractId = getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            USED_CONTRACT_ID.add(contractId);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var response = CONSUMER.queryData(contractId, Map.of(), INTERNAL_SERVER_ERROR.getStatusCode(), TransferErrorResponse.class);
                assertThat(response.getErrors()).containsExactly("Received code transferring HTTP data: 500 - Server Error.");
            });
        }

        @Test
        void transfer_whenContractExpiration_shouldTerminateTransferProcessAtExpiration() {
            var message = UUID.randomUUID().toString();
            var expectedMsg = Map.of("message", message);
            Map<String, String> queryParams = Map.of("message", message);
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, ASSET_ID_REST_20_SEC_API);
            var contractId = getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            USED_CONTRACT_ID.add(contractId);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var data = CONSUMER.queryData(contractId, queryParams, 200, Object.class);
                assertThat(data).isEqualTo(expectedMsg);
            });

            // check that after some time, the transfer process gets deprovisioned
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var state = CONSUMER.participantClient().getTransferProcessState(transferProcessId);
                assertThat(state).isEqualTo(TERMINATED.name());
            });

            await().atMost(TEST_TIMEOUT).untilAsserted(() -> CONSUMER.queryData(contractId, Map.of(), 403, TransferErrorResponse.class));
        }

        @Test
        void transfer_forRestrictedDiscoveryAssets() {
            var message = UUID.randomUUID().toString();
            var expectedMsg = Map.of("message", message);
            Map<String, String> queryParams = Map.of("message", message);
            var negoId = CONSUMER.participantClient().initContractNegotiation(PROVIDER.participantClient(), ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED);
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, ASSET_ID_REST_API_ROUTE_DOMAIN_RESTRICTED);
            var contractId = getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            USED_CONTRACT_ID.add(contractId);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var data = CONSUMER.queryData(contractId, queryParams, 200, Object.class);
                assertThat(data).isEqualTo(expectedMsg);
            });
        }

        @Test
        void transfer_forRestrictedDiscoveryAssets_NotAvailable() {
            var negoId = CONSUMER.participantClient().initContractNegotiation(PROVIDER.participantClient(), ASSET_ID_REST_API_TRAVEL_DOMAIN_RESTRICTED);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var state = CONSUMER.participantClient().getContractNegotiationState(negoId);
                assertThat(state).isEqualTo(ContractNegotiationStates.TERMINATED.name());
            });
        }

        @Test
        void transfer_whenPolicyNotMatched_shouldTerminate() {
            var negoId = CONSUMER.participantClient().initContractNegotiation(PROVIDER.participantClient(), POLICY_RESTRICTED_API);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var state = CONSUMER.participantClient().getContractNegotiationState(negoId);
                assertThat(state).isEqualTo(ContractNegotiationStates.TERMINATED.name());
            });
        }

        @Test
        void contractNegotiation_delete_shouldRemoveNegotiation() {
            // Initiate a contract negotiation that will be terminated (policy not matched)
            var negoId = CONSUMER.participantClient().initContractNegotiation(PROVIDER.participantClient(), POLICY_RESTRICTED_API);
            
            // Wait for negotiation to reach TERMINATED state
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var state = CONSUMER.participantClient().getContractNegotiationState(negoId);
                assertThat(state).isEqualTo(ContractNegotiationStates.TERMINATED.name());
            });

            // Verify negotiation exists before deletion
            CONSUMER.participantClient().baseManagementRequest()
                    .when()
                    .get("/v3/contractnegotiations/" + negoId)
                    .then()
                    .log().ifError()
                    .statusCode(200);

            // Delete the contract negotiation
            CONSUMER.participantClient().baseManagementRequest()
                    .when()
                    .delete("/v3/contractnegotiations/" + negoId)
                    .then()
                    .log().ifError()
                    .statusCode(204);

            // Verify negotiation no longer exists
            CONSUMER.participantClient().baseManagementRequest()
                    .when()
                    .get("/v3/contractnegotiations/" + negoId)
                    .then()
                    .log().ifError()
                    .statusCode(404);
        }

        public static String getContractIdFromTransferProcess(AbstractParticipant consumer, String transferProcessId) {
            return consumer.participantClient().baseManagementRequest()
                    .when()
                    .get("/v3/transferprocesses/" + transferProcessId)
                    .then()
                    .statusCode(200)
                    .extract().body().jsonPath().getString("contractId");
        }

        public static class TransferTestProvider implements ArgumentsProvider {

            @Override
            public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
                var msg = UUID.randomUUID().toString();
                return Stream.of(
                        Arguments.of(Map.of("message", msg), ASSET_ID_REST_API, msg),
                        Arguments.of(Map.of("message", msg), ASSET_ID_REST_API_DOMAIN, msg),
                        Arguments.of(Map.of("message", msg), ASSET_ID_REST_API_OAUTH2, msg),
                        Arguments.of(Map.of(), ASSET_ID_REST_API_EMBEDDED_QUERY_PARAMS, EMBEDDED_QUERY_PARAM)
                );
            }
        }

        protected void transferProcess(AbstractParticipant consumer, AbstractParticipant provider, String contractId) {
            var providerUrl = provider.controlPlaneProtocolUrl();
            var consumerDid = consumer.did();
            var body = Map.of(
                    "@context", Map.of(
                            "@vocab", "https://w3id.org/edc/v0.0.1/ns/"
                    ),
                    "@type", "TransferRequest",
                    "counterPartyAddress", providerUrl,
                    "protocol", "dataspace-protocol-http",
                    "connectorId", consumerDid,
                    "contractId", contractId,
                    "transferType", "HttpData-PULL"
            );
            consumer.participantClient().baseManagementRequest()
                    .contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/v3/transferprocesses")
                    .then()
                    .log().ifError()
                    .statusCode(200);
        }

        private void retireAgreement(AbstractParticipant participant, String contractId, String reason) {
            var body = Map.of(
                    "@context", Map.of(
                            "eonax", "https://w3id.org/eonax/v0.0.1/ns/",
                            "edc", "https://w3id.org/edc/v0.0.1/ns/"
                    ),
                    "edc:agreementId", contractId,
                    "eonax:reason", reason
            );

            participant.participantClient().baseManagementRequest()
                    .contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/v3/contractagreements/retirements")
                    .then()
                    .log().ifError()
                    .statusCode(204);
        }

        private void reactivateAgreement(AbstractParticipant participant, String contractId) {
            participant.participantClient().baseManagementRequest()
                    .contentType(ContentType.JSON)
                    .when()
                    .delete("/v3/contractagreements/retirements/" + contractId)
                    .then()
                    .log().ifError()
                    .statusCode(204);
        }

        @Test
        void transfer_retireAgreement_shouldBlockFurtherAccess() {
            var message = UUID.randomUUID().toString();
            Map<String, String> queryParams = Map.of("message", message);
            var expectedMsg = Map.of("message", message);

            // Negotiate and transfer
            var transferProcessId = negotiationContractAndStartTransfer(CONSUMER, PROVIDER, ASSET_ID_REST_API);
            var contractId = getContractIdFromTransferProcess(CONSUMER, transferProcessId);
            USED_CONTRACT_ID.add(contractId);
            // Confirm data access works
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var data = CONSUMER.queryData(contractId, queryParams, 200, Map.class);
                assertThat(data).isEqualTo(expectedMsg);
            });

            // Retire the agreement
            retireAgreement(PROVIDER, contractId, "Test retirement reason");

            // Confirm data access is blocked after retirement
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var error = CONSUMER.queryData(contractId, queryParams, 403, TransferErrorResponse.class);
                assertThat(error.getErrors()).anyMatch(msg -> msg.contains("No EDR satisfying criterion"));
            });
            // Reactivate the agreement
            reactivateAgreement(PROVIDER, contractId);
            // Confirm data access is opened after reactivation
            transferProcess(CONSUMER, PROVIDER, contractId);
            await().atMost(TEST_TIMEOUT).untilAsserted(() -> {
                var data = CONSUMER.queryData(contractId, queryParams, 200, Map.class);
                assertThat(data).isEqualTo(expectedMsg);
            });
        }
    }


    // Using order here should be considered technical debt and be tackled in the future.
    // Here I am using order to make the report test be the first one to be executed.
    // Unfortunately, our tests are not independent between each other in the sense that the data created is cleaned after their execution
    // If this test runs after the remaining ones the validation that is used to generate the report fails so if I run it before the others I have a clean state in the DB.
    // FDPT-84293
    @Nested
    @Order(1)
    class ReportTest {

        public static String buildTelemetryJson(
                String id,
                String contractId,
                String participantDid,
                int responseStatusCode,
                int msgSize,
                Integer csvId,
                Timestamp timestamp
        ) {
            try {
                ObjectNode root = OBJECT_MAPPER.createObjectNode();
                root.put("id", id);
                root.put("contractId", contractId);
                root.put("participantId", participantDid);
                root.put("responseStatusCode", responseStatusCode);
                root.put("msgSize", msgSize);

                if (csvId != null) {
                    root.put("csvId", csvId);
                } else {
                    root.putNull("csvId");
                }

                root.put("timestamp", timestamp.toInstant().toString());

                return OBJECT_MAPPER.writeValueAsString(root);

            } catch (Exception e) {
                throw new RuntimeException("Error building telemetry JSON", e);
            }
        }

        public static String buildGenerationJson(
                String participantName,
                Integer month,
                Integer year
        ) {
            try {
                ObjectNode root = OBJECT_MAPPER.createObjectNode();
                root.put("participantName", participantName);
                root.put("month", month);
                root.put("year", year);

                return OBJECT_MAPPER.writeValueAsString(root);

            } catch (Exception e) {
                throw new RuntimeException("Error building generation JSON", e);
            }
        }

        @Test
        void testReportGenerationSucceeds() {
            String ctId = UUID.randomUUID().toString();
            int month = 9;
            int year = 2025;
            Timestamp timestamp = Timestamp.valueOf(LocalDateTime.of(year, month, 20, 20, 18));
            String eventConsumer = buildTelemetryJson("1", ctId, CONSUMER.did(), 200, 20, null, timestamp);
            String eventProvider = buildTelemetryJson("2", ctId, PROVIDER.did(), 200, 20, null, timestamp);

            given()
                    .baseUri("%s".formatted(AUTHORITY.telemetryUrl()))
                    .contentType(JSON)
                    .body(eventConsumer)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            given()
                    .baseUri("%s".formatted(AUTHORITY.telemetryUrl()))
                    .contentType(JSON)
                    .body(eventProvider)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            String generationJson = buildGenerationJson(CONSUMER.name(), month, year);

            given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .body(generationJson)
                    .contentType(JSON)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            Map<String, Object> getReportParams = new HashMap<>();
            getReportParams.put("month", month);
            getReportParams.put("year", year);

            Response responseBody = given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .params(getReportParams)
                    .contentType(JSON)
                    .header("Authorization", "Bearer " + dummyJwt)
                    .get()
                    .then()
                    .log().ifError()
                    .statusCode(200).contentType(containsString("text/csv"))
                    .extract().response();

            String expectedCsvReportBuilder = REPORT_HEADER_WITHOUT_COUNTERPARTY_INFO + "\n" +
                    ctId + "," + PROVIDER.name() + "," + 200 + "," + 0.02 + "," + 1;
            assertEquals(expectedCsvReportBuilder, responseBody.getBody().asString().trim());

            // Validates that if we have multiple roles in the JWT it still returns the correct report
            Response responseBodyForMultipleRoles = given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .params(getReportParams)
                    .contentType(JSON)
                    .header("Authorization", "Bearer " + dummyJwtWithMultipleRoles)
                    .get()
                    .then()
                    .log().ifError()
                    .statusCode(200).contentType(containsString("text/csv"))
                    .extract().response();

            assertEquals(expectedCsvReportBuilder, responseBodyForMultipleRoles.getBody().asString().trim());
        }

        @Test
        void testReportGenerationWithOnlyOnePartySucceeds() {
            String ctId = UUID.randomUUID().toString();
            int month = 12;
            int year = 2025;
            Timestamp timestamp = Timestamp.valueOf(LocalDateTime.of(year, month, 20, 20, 18));
            // Only creates an event for the consumer and not the provider to trigger a failure in the report generation validation
            String eventConsumer = buildTelemetryJson("3", ctId, CONSUMER.did(), 400, 20, null, timestamp);

            given()
                    .baseUri("%s".formatted(AUTHORITY.telemetryUrl()))
                    .contentType(JSON)
                    .body(eventConsumer)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            String generationJson = buildGenerationJson(CONSUMER.name(), month, year);

            given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .body(generationJson)
                    .contentType(JSON)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            Map<String, Object> getReportParams = new HashMap<>();
            getReportParams.put("month", month);
            getReportParams.put("year", year);

            Response responseBody = given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .params(getReportParams)
                    .contentType(JSON)
                    .header("Authorization", "Bearer " + dummyJwt)
                    .get()
                    .then()
                    .log().ifError()
                    .statusCode(200).contentType(containsString("text/csv"))
                    .extract().response();

            // If there are no events received from the counterparty side, it will not be possible to pinpoint the name
            // of the counterparty so it will be marked as N/A
            String expectedCsvReportBuilder = REPORT_HEADER_WITHOUT_COUNTERPARTY_INFO + "\n" +
                    ctId + ",N/A," + 400 + "," + 0.02 + "," + 1;
            assertEquals(expectedCsvReportBuilder, responseBody.getBody().asString().trim());
        }

        @Test
        void testRetrieveReportWithNonExistentParticipantFails() {
            String ctId = UUID.randomUUID().toString();
            int month = 1;
            int year = 2025;
            Timestamp timestamp = Timestamp.valueOf(LocalDateTime.of(year, month, 20, 20, 18));
            // Only creates an event for the consumer and not the provider to trigger a failure in the report generation validation
            String eventConsumer = buildTelemetryJson("4", ctId, CONSUMER.did(), 200, 20, null, timestamp);

            given()
                    .baseUri("%s".formatted(AUTHORITY.telemetryUrl()))
                    .contentType(JSON)
                    .body(eventConsumer)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            String generationJson = buildGenerationJson(CONSUMER.name(), month, year);

            given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .body(generationJson)
                    .contentType(JSON)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(201);

            Map<String, Object> getReportParams = new HashMap<>();
            getReportParams.put("month", month);
            getReportParams.put("year", year);

            given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .params(getReportParams)
                    .contentType(JSON)
                    .header("Authorization", "Bearer " + dummyJwtNonExistentParticipant)
                    .get()
                    .then()
                    .log().ifError()
                    .statusCode(403);
        }

        @Test
        void testRetrieveNonExistentReportFromExistentParticipantFails() {
            int month = 2;
            int year = 2024;

            Map<String, Object> getReportParams = new HashMap<>();
            getReportParams.put("month", month);
            getReportParams.put("year", year);

            given()
                    .baseUri("%s".formatted(AUTHORITY.csvManagerUrl()))
                    .params(getReportParams)
                    .contentType(JSON)
                    .header("Authorization", "Bearer " + dummyJwt)
                    .get()
                    .then()
                    .log().ifError()
                    .statusCode(404);
        }
    }
}
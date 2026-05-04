package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_CONTRACT;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_PARTICIPANT;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Secure Integration Test for the Kafka Telemetry pipeline.
 * <p>
 * This test validates the full SASL/PLAIN authentication flow using the
 * {@link DynamicTokenCallbackHandler} against a Kafka broker that REQUIRES authentication.
 * <p>
 * Production flow tested:
 * <pre>
 *   1. Extension creates a managed DynamicTokenCallbackHandler (createManaged())
 *   2. Extension stores a JWT token in the static TOKEN_REGISTRY (updateToken())
 *   3. KafkaProducer creates a NEW handler instance via reflection
 *   4. Kafka calls handler.configure() → handler reads instance.id from JAAS config
 *   5. PlainLoginModule calls handler.handle([NameCallback, PasswordCallback])
 *   6. Handler looks up TOKEN_REGISTRY[instance.id] → injects JWT value as password
 *   7. Broker validates user "oauth2" with password = JWT token value
 * </pre>
 * <p>
 * Uses {@code confluentinc/cp-kafka} image because it natively supports SASL configuration
 * via environment variables.
 */
@ExtendWith(MockitoExtension.class)
@Testcontainers
class KafkaTelemetryRecordPublisherSecureIT {

    private static final String SASL_USER = "oauth2";
    private static final String VALID_TOKEN_VALUE = "valid-jwt-token-for-testing";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin-secret";

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT")
            .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS", "PLAIN")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_PLAIN_SASL_JAAS_CONFIG",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"" + ADMIN_USERNAME + "\" password=\"" + ADMIN_PASSWORD + "\" "
                            + "user_" + ADMIN_USERNAME + "=\"" + ADMIN_PASSWORD + "\" "
                            + "user_" + SASL_USER + "=\"" + VALID_TOKEN_VALUE + "\";");

    private final TypeManager typeManager = new JacksonTypeManager();

    @Mock
    private Monitor monitor;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private KafkaTelemetryRecordPublisher publisher;
    private DynamicTokenCallbackHandler callbackHandler;
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "secure-test-topic-" + UUID.randomUUID();

        callbackHandler = DynamicTokenCallbackHandler.createManaged();
        callbackHandler.updateToken(buildToken(VALID_TOKEN_VALUE));

        producer = new KafkaProducer<>(buildAuthenticatedProducerProperties(callbackHandler));
        publisher = new KafkaTelemetryRecordPublisher(producer, topic, typeManager, monitor);

        consumer = new KafkaConsumer<>(buildAdminConsumerProperties());
        consumer.subscribe(Collections.singletonList(topic));
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close();
        }
        if (consumer != null) {
            consumer.close();
        }
        if (callbackHandler != null) {
            callbackHandler.close();
        }
    }

    // ═══ SASL/PLAIN Authentication Success ═══════════════════════════════════

    @Test
    void shouldAuthenticateAndPublishWhenValidTokenIsInjectedViaHandler() {
        TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isTrue();
        verify(monitor, never()).severe(anyString());
        verify(monitor, never()).warning(anyString());
    }

    @Test
    void shouldDeliverMessageThroughSecuredBrokerWhenTokenIsValid() {
        TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);
        publisher.sendRecord(record);

        ConsumerRecords<String, String> records = pollUntilNonEmpty();

        assertThat(records).hasSize(1);
        verify(monitor, never()).severe(anyString());
    }

    @Test
    void shouldPreservePayloadIntegrityThroughSecuredBroker() {
        TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);
        publisher.sendRecord(record);

        ConsumerRecords<String, String> records = pollUntilNonEmpty();
        String value = records.iterator().next().value();
        TelemetryRecord consumed = typeManager.readValue(value, TelemetryRecord.class);

        assertThat(consumed.getProperties().get("contractId")).isEqualTo(TEST_CONTRACT);
    }

    @Test
    void shouldUseContractIdAsKafkaKeyThroughSecuredBroker() {
        TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);
        publisher.sendRecord(record);

        ConsumerRecords<String, String> records = pollUntilNonEmpty();

        assertThat(records.iterator().next().key()).isEqualTo(TEST_CONTRACT);
    }

    @Test
    void shouldProduceNullKafkaKeyWhenContractIdIsAbsent() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(KafkaTestUtils.TYPE)
                .property(KafkaTestUtils.PARTICIPANT_ID, TEST_PARTICIPANT)
                .build();

        publisher.sendRecord(record);

        ConsumerRecords<String, String> records = pollUntilNonEmpty();

        assertThat(records.iterator().next().key()).isNull();
        // No warning should be logged because contractId is simply absent (not invalid)
        verify(monitor, never()).warning(contains("contractId"));
    }

    @Test
    void shouldLogWarningAndUseNullKeyWhenContractIdIsNotString() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(KafkaTestUtils.TYPE)
                .property("contractId", 12345)
                .property(KafkaTestUtils.PARTICIPANT_ID, TEST_PARTICIPANT)
                .build();

        publisher.sendRecord(record);

        ConsumerRecords<String, String> records = pollUntilNonEmpty();
        assertThat(records.iterator().next().key()).isNull();

        verify(monitor, times(1)).warning(contains("contractId is not a String"));
    }

    // ═══ SASL/PLAIN Authentication Failure ═══════════════════════════════════

    @Test
    void shouldFailToPublishWhenNoTokenIsRegisteredInHandler() {
        DynamicTokenCallbackHandler emptyHandler = DynamicTokenCallbackHandler.createManaged();

        try (KafkaProducer<String, String> unauthProducer =
                     new KafkaProducer<>(buildTimeoutLimitedProducerProperties(emptyHandler))) {

            var unauthPublisher = new KafkaTelemetryRecordPublisher(unauthProducer, topic, typeManager, monitor);
            TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

            Boolean result = unauthPublisher.sendRecord(record);

            assertThat(result).isFalse();
            // Expect a severe log because the handler throws IllegalStateException
            verify(monitor, times(1)).severe(contains("Failed to send telemetry record"));
        } finally {
            emptyHandler.close();
        }
    }

    @Test
    void shouldFailToPublishWhenTokenValueDoesNotMatchBrokerPassword() {
        DynamicTokenCallbackHandler wrongHandler = DynamicTokenCallbackHandler.createManaged();
        wrongHandler.updateToken(buildToken("wrong-password-value"));

        try (KafkaProducer<String, String> wrongProducer =
                     new KafkaProducer<>(buildTimeoutLimitedProducerProperties(wrongHandler))) {

            var wrongPublisher = new KafkaTelemetryRecordPublisher(wrongProducer, topic, typeManager, monitor);
            TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

            Boolean result = wrongPublisher.sendRecord(record);

            assertThat(result).isFalse();
            // Broker rejects credentials, send fails with ExecutionException
            verify(monitor, times(1)).severe(contains("Failed to send telemetry record"));
        } finally {
            wrongHandler.close();
        }
    }

    // ═══ Token Refresh ════════════════════════════════════════════════════════

    @Test
    void shouldContinuePublishingAfterTokenIsRefreshed() {
        // NOTE: An already-connected KafkaProducer does NOT re-authenticate as long as the TCP connection
        // remains alive. To force a fresh SASL handshake, we must create a new producer at each step.
        // This test verifies that the updated token in TOKEN_REGISTRY is used for new connections.

        // Step 1: initial publish succeeds with valid token
        TelemetryRecord record1 = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);
        Boolean firstResult = publisher.sendRecord(record1);
        assertThat(firstResult).isTrue();

        // Step 2: replace token with an invalid one and create a new producer
        callbackHandler.updateToken(buildToken("expired-or-revoked-token"));

        try (KafkaProducer<String, String> expiredProducer =
                     new KafkaProducer<>(buildTimeoutLimitedProducerProperties(callbackHandler))) {

            var expiredPublisher = new KafkaTelemetryRecordPublisher(expiredProducer, topic, typeManager, monitor);
            TelemetryRecord record2 = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

            Boolean expiredResult = expiredPublisher.sendRecord(record2);
            assertThat(expiredResult).isFalse();
        }

        // Step 3: refresh to a new valid token, publish succeeds again with a fresh producer
        callbackHandler.updateToken(buildToken(VALID_TOKEN_VALUE));

        try (KafkaProducer<String, String> refreshedProducer =
                     new KafkaProducer<>(buildAuthenticatedProducerProperties(callbackHandler))) {

            var refreshedPublisher = new KafkaTelemetryRecordPublisher(refreshedProducer, topic, typeManager, monitor);
            TelemetryRecord record3 = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

            Boolean refreshedResult = refreshedPublisher.sendRecord(record3);
            assertThat(refreshedResult).isTrue();
        }
    }

    private Properties buildAuthenticatedProducerProperties(DynamicTokenCallbackHandler handler) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        applySaslConfig(props, handler);
        return props;
    }

    private Properties buildTimeoutLimitedProducerProperties(DynamicTokenCallbackHandler handler) {
        Properties props = buildAuthenticatedProducerProperties(handler);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        return props;
    }

    private void applySaslConfig(Properties props, DynamicTokenCallbackHandler handler) {
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");

        String jaasConfig = String.format(
                "org.apache.kafka.common.security.plain.PlainLoginModule required %s=\"%s\";",
                DynamicTokenCallbackHandler.INSTANCE_ID_KEY,
                handler.getInstanceId()
        );
        props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
        props.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS, DynamicTokenCallbackHandler.class.getName());
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, DynamicTokenCallbackHandler.class.getName());
    }

    private Properties buildAdminConsumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "secure-it-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        props.put(SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"" + ADMIN_USERNAME + "\" password=\"" + ADMIN_PASSWORD + "\";");

        return props;
    }

    private OAuthBearerToken buildToken(String tokenValue) {
        long nowMs = System.currentTimeMillis();
        long oneHourMs = 3_600_000L;

        return new OauthBearerTokenImpl(
                tokenValue,
                Set.of("telemetry"),
                nowMs + oneHourMs,
                SASL_USER,
                nowMs
        );
    }

    private ConsumerRecords<String, String> pollUntilNonEmpty() {
        AtomicReference<ConsumerRecords<String, String>> received = new AtomicReference<>();
        await()
                .atMost(30, SECONDS)
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    assertThat(records).isNotEmpty();
                    received.set(records);
                });
        return received.get();
    }
}

package org.eclipse.dse.edc.telemetrystorage;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEventStore;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_CONTRACT;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_MESSAGE;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_PARTICIPANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class KafkaTelemetryConsumerExtensionIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.8.0"));

    private String topic;

    @Mock
    private TelemetryEventStore store;

    @Mock
    private Monitor monitor;

    private final TypeManager typeManager = new JacksonTypeManager();

    private KafkaTelemetryConsumerExtension kafkaTelemetryConsumerExtension;
    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() throws Exception {
        kafkaTelemetryConsumerExtension = new KafkaTelemetryConsumerExtension();
        injectFields();
        producer = new KafkaProducer<>(buildProducerProperties());
    }

    @AfterEach
    void tearDown() {
        if (kafkaTelemetryConsumerExtension != null) {
            kafkaTelemetryConsumerExtension.shutdown();
        }
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void shouldConsumeAndPersistRecord() throws Exception {
        when(store.save(any())).thenReturn(StoreResult.success());

        startAndAwaitConsumer();

        TelemetryRecord record = buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, TEST_MESSAGE);
        sendRecord(record);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            verify(store, atLeastOnce()).save(argThat(event ->
                    TEST_CONTRACT.equals(event.contractId())))
        );
    }

    @Test
    void shouldHandlePermanentFailureAndSkip() throws Exception {
        when(store.save(any())).thenReturn(StoreResult.success());

        startAndAwaitConsumer();

        producer.send(new ProducerRecord<>(topic, "key", "invalid-json")).get();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(monitor, atLeastOnce()).severe(anyString())
        );

        TelemetryRecord validRecord = buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, TEST_MESSAGE);
        sendRecord(validRecord);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(store, atLeastOnce()).save(argThat(event ->
                        TEST_CONTRACT.equals(event.contractId())))
        );
    }

    @Test
    void shouldHandleTransientFailureAndRetry() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        when(store.save(any())).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() < 1) {
                return StoreResult.generalError("Transient DB Error");
            }
            return StoreResult.success();
        });

        startAndAwaitConsumer();

        TelemetryRecord record = buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, TEST_MESSAGE);
        sendRecord(record);

        // Expect at least 2 calls to save (first failed, second succeed after backoff)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(store, times(2)).save(argThat(event ->
                        TEST_CONTRACT.equals(event.contractId())))
        );
    }

    @Test
    void shouldSkipDuplicateRecord() throws Exception {
        when(store.save(any())).thenReturn(StoreResult.alreadyExists("already persisted"));

        startAndAwaitConsumer();

        TelemetryRecord record = buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, TEST_MESSAGE);
        sendRecord(record);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(monitor, atLeastOnce()).warning(contains("DUPLICATE"));
            verify(store, times(1)).save(any());
        });
    }

    @Test
    void shouldSkipInvalidRecord() throws Exception {
        startAndAwaitConsumer();

        TelemetryRecord record = buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);
        sendRecord(record);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(monitor, atLeastOnce()).severe(anyString());
            verify(store, never()).save(any());
        });
    }

    @Test
    void shouldStopConsumerWhenMaxRetriesExhausted() throws Exception {
        setField("initialRetryDelayMs", 1L);
        setField("maxRetryDelayMs", 5L);

        when(store.save(any())).thenReturn(StoreResult.generalError("DB DOWN"));

        startAndAwaitConsumer();
        sendRecord(buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, TEST_MESSAGE));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(store, times(5)).save(any());
            verify(monitor, atLeastOnce()).severe(anyString());
            assertThat(kafkaTelemetryConsumerExtension.isRunning()).isFalse();
        });
    }

    @Test
    void shouldShutdownCleanly() {
        startAndAwaitConsumer();
        kafkaTelemetryConsumerExtension.shutdown();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(kafkaTelemetryConsumerExtension.isRunning()).isFalse()
        );
    }

    private void injectFields() throws Exception {
        topic = KafkaTestUtils.TOPIC + "-" + UUID.randomUUID();
        setField("store", store);
        setField("typeManager", typeManager);
        setField("monitor", monitor);
        setField("bootstrapServers", KAFKA.getBootstrapServers());
        setField("topic", topic);
        setField("groupId", "test-group-" + UUID.randomUUID());
    }

    private void startAndAwaitConsumer() {
        kafkaTelemetryConsumerExtension.start();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> kafkaTelemetryConsumerExtension.isRunning());
    }

    private void sendRecord(TelemetryRecord record) throws Exception {
        String json = typeManager.writeValueAsString(record);
        producer.send(new ProducerRecord<>(topic, record.getId(), json)).get();
    }

    private TelemetryRecord buildTelemetryRecord(String contractId, String participantId, String messageId) {
        return KafkaTestUtils.buildTelemetryRecord(contractId, participantId, messageId);
    }

    private Properties buildProducerProperties() {
        return KafkaTestUtils.buildProducerProperties(KAFKA.getBootstrapServers());
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = kafkaTelemetryConsumerExtension.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(kafkaTelemetryConsumerExtension, value);
    }
}

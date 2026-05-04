package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.CONTRACT_ID;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_CONTRACT;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_PARTICIPANT;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.verify;
import static java.util.concurrent.TimeUnit.SECONDS;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class KafkaTelemetryRecordPublisherIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.8.0"));

    private static final String MESSAGE_ID_KEY = "messageId";
    private final TypeManager typeManager = new JacksonTypeManager();

    @Mock
    private Monitor monitor;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private KafkaTelemetryRecordPublisher publisher;
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "test-topic-" + UUID.randomUUID();
        producer = new KafkaProducer<>(KafkaTestUtils.buildProducerProperties(KAFKA.getBootstrapServers()));
        publisher = new KafkaTelemetryRecordPublisher(producer, topic, typeManager, monitor);
        consumer = new KafkaConsumer<>(buildIsolatedConsumerProperties());
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
    }

    @Test
    void shouldReturnTrueWhenRecordIsSuccessfullySentToKafka() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        Boolean result = publisher.sendRecord(record);
        assertThat(result).isTrue();
    }

    @Test
    void shouldDeliverExactlyOneMessageWhenOneRecordIsSent() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecords<String, String> records = pollUntilNonEmpty();
        assertThat(records).hasSize(1);
    }

    @Test
    void shouldUseContractIdAsKafkaKeyWhenSendingRecord() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecord<String, String> received = pollAnyRecordMatching(TEST_CONTRACT);
        assertThat(received.key()).isEqualTo(TEST_CONTRACT);
    }

    @Test
    void shouldInjectNonNullMessageIdWhenSendingRecord() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecord<String, String> received = pollUntilNonEmpty().iterator().next();
        TelemetryRecord consumed = typeManager.readValue(received.value(), TelemetryRecord.class);
        assertThat(consumed.getProperties().get(MESSAGE_ID_KEY)).isNotNull();
    }

    @Test
    void shouldPreserveContractIdWhenDeserializingConsumedRecord() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecord<String, String> received = pollAnyRecordMatching(TEST_CONTRACT);
        TelemetryRecord consumed = typeManager.readValue(received.value(), TelemetryRecord.class);
        assertThat(consumed.getProperties().get(CONTRACT_ID)).isEqualTo(TEST_CONTRACT);
    }

    @Test
    void shouldPublishToCorrectTopicWhenSendingRecord() {
        TelemetryRecord record = buildRecord(TEST_CONTRACT, TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecords<String, String> records = pollUntilNonEmpty();
        assertThat(records).allSatisfy(r -> assertThat(r.topic()).isEqualTo(topic));
    }

    @Test
    void shouldSendWithNullKeyWhenContractIdIsAbsentFromProperties() {
        TelemetryRecord record = buildRecordWithoutContractId(TEST_PARTICIPANT);
        publisher.sendRecord(record);
        ConsumerRecords<String, String> records = pollUntilNonEmpty();
        assertThat(records).extracting(ConsumerRecord::key).containsNull();
    }

    @Test
    void shouldReturnFalseWhenBrokerIsUnreachable() {
        try (KafkaProducer<String, String> brokenProducer = new KafkaProducer<>(
                KafkaTestUtils.buildBrokenProducerProperties())) {
            KafkaTelemetryRecordPublisher brokenPublisher = new KafkaTelemetryRecordPublisher(
                    brokenProducer, topic, typeManager, monitor);
            Boolean result = brokenPublisher.sendRecord(
                    KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, UUID.randomUUID().toString()));
            assertThat(result).isFalse();
        }
    }

    @Test
    void shouldReturnFalseAndLogWarningWhenTopicIsNull() {
        KafkaTelemetryRecordPublisher nullTopicPublisher = new KafkaTelemetryRecordPublisher(
                producer, null, typeManager, monitor);
        Boolean result = nullTopicPublisher.sendRecord(buildRecord(TEST_CONTRACT, TEST_PARTICIPANT));
        assertThat(result).isFalse();
        verify(monitor).warning(anyString());
    }

    @Test
    void shouldReturnFalseAndLogWarningWhenProducerIsNull() {
        KafkaTelemetryRecordPublisher nullProducerPublisher = new KafkaTelemetryRecordPublisher(
                null, topic, typeManager, monitor);
        Boolean result = nullProducerPublisher.sendRecord(buildRecord(TEST_CONTRACT, TEST_PARTICIPANT));
        assertThat(result).isFalse();
        verify(monitor).warning(anyString());
    }

    @Test
    void shouldReturnFalseAndLogWarningWhenPublisherIsClosed() {
        publisher.close();
        Boolean result = publisher.sendRecord(buildRecord(TEST_CONTRACT, TEST_PARTICIPANT));
        assertThat(result).isFalse();
        verify(monitor).warning(anyString());
    }

    @Test
    void shouldLogWarningAndDoNothingWhenCloseIsCalledTwice() {
        publisher.close();
        publisher.close(); // second call should not throw
        verify(monitor, atMostOnce()).debug(anyString());
    }

    private Properties buildIsolatedConsumerProperties() {
        Properties props = KafkaTestUtils.buildConsumerProperties(KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
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

    private ConsumerRecord<String, String> pollAnyRecordMatching(String key) {
        AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
        await()
                .atMost(30, SECONDS)
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> r : records) {
                        if (Objects.equals(r.key(), key)) {
                            found.set(r);
                            return;
                        }
                    }
                    assertThat(found.get()).isNotNull();
                });
        return found.get();
    }

    private TelemetryRecord buildRecord(String contractId, String participantId) {
        return KafkaTestUtils.buildTelemetryRecord(contractId, participantId, null);
    }

    private TelemetryRecord buildRecordWithoutContractId(String participantId) {
        return TelemetryRecord.Builder.newInstance()
                .type("test-type")
                .property("participantId", participantId)
                .build();
    }
}

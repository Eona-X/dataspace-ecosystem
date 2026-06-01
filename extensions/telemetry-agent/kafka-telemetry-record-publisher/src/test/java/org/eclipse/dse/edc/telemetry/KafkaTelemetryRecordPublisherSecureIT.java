package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
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

import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_CONTRACT;
import static org.eclipse.dse.edc.telemetry.testutil.KafkaTestUtils.TEST_PARTICIPANT;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Secure Integration Test for the Kafka Telemetry pipeline.
 */
@ExtendWith(MockitoExtension.class)
@Testcontainers
class KafkaTelemetryRecordPublisherSecureIT {

    private static final String SASL_USER = "dev-telemetry";
    private static final String SASL_PASSWORD = "password";
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
                            + "user_" + SASL_USER + "=\"" + SASL_PASSWORD + "\";");

    private final TypeManager typeManager = new JacksonTypeManager();

    @Mock
    private Monitor monitor;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private KafkaTelemetryRecordPublisher publisher;
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "secure-test-topic-" + UUID.randomUUID();

        producer = new KafkaProducer<>(buildAuthenticatedProducerProperties(SASL_USER, SASL_PASSWORD));
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
    }

    @Test
    void shouldAuthenticateAndPublishWhenValidCredentialsAreProvided() {
        TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

        Boolean result = publisher.sendRecord(record);

        assertThat(result).isTrue();
        verify(monitor, never()).severe(anyString());
    }

    @Test
    void shouldFailToPublishWhenPasswordIsWrong() {
        try (KafkaProducer<String, String> wrongProducer =
                     new KafkaProducer<>(buildTimeoutLimitedProducerProperties(SASL_USER, "wrong-password"))) {

            var wrongPublisher = new KafkaTelemetryRecordPublisher(wrongProducer, topic, typeManager, monitor);
            TelemetryRecord record = KafkaTestUtils.buildTelemetryRecord(TEST_CONTRACT, TEST_PARTICIPANT, null);

            Boolean result = wrongPublisher.sendRecord(record);

            assertThat(result).isFalse();
            verify(monitor, times(1)).severe(contains("Failed to send telemetry record"));
        }
    }

    private Properties buildAuthenticatedProducerProperties(String username, String password) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");

        String jaasConfig = String.format(
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                username,
                password
        );
        props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);

        return props;
    }

    private Properties buildTimeoutLimitedProducerProperties(String username, String password) {
        Properties props = buildAuthenticatedProducerProperties(username, password);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        return props;
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
}

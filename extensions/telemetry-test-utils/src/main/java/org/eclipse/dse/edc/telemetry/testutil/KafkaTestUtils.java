package org.eclipse.dse.edc.telemetry.testutil;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;

import java.util.Properties;
import java.util.UUID;

public final class KafkaTestUtils {

    public static final String TOPIC = "telemetry-test";
    public static final String TYPE = "test-type";
    public static final String CONTRACT_ID = "contractId";
    public static final String PARTICIPANT_ID = "participantId";
    public static final String MESSAGE_ID = "messageId";
    public static final String TEST_CONTRACT = "test-contract";
    public static final String TEST_PARTICIPANT = "test-participant";
    public static final String TEST_MESSAGE = UUID.randomUUID().toString();
    public static final String TIMESTAMP = "timestamp";

    private KafkaTestUtils() {
    }

    public static Properties buildProducerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.RETRIES_CONFIG, 3);
        return properties;
    }

    public static Properties buildConsumerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }

    public static Properties buildBrokenProducerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        return properties;
    }

    public static TelemetryRecord buildTelemetryRecord(String contractId, String participantId, String messageId) {
        TelemetryRecord.Builder builder = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TYPE)
                .property(CONTRACT_ID, contractId)
                .property(PARTICIPANT_ID, participantId)
                .property(TIMESTAMP, System.currentTimeMillis());
        if (messageId != null) {
            builder.property(MESSAGE_ID, messageId);
        }
        return builder.build();
    }
}

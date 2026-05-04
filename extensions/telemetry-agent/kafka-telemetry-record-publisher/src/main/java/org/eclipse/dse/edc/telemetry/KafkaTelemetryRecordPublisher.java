package org.eclipse.dse.edc.telemetry;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordPublisher;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.TypeManager;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka-based implementation of {@link TelemetryRecordPublisher}.
 * Serializes {@link TelemetryRecord} to JSON and sends it to a Kafka topic.
 */
public class KafkaTelemetryRecordPublisher implements TelemetryRecordPublisher {

    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final TypeManager typeManager;
    private final Monitor monitor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public KafkaTelemetryRecordPublisher(KafkaProducer<String, String> producer, String topic, TypeManager typeManager, Monitor monitor) {
        this.producer = producer;
        this.topic = topic;
        this.typeManager = typeManager;
        this.monitor = monitor;
    }

    @Override
    public Boolean sendRecord(TelemetryRecord record) {
        try {
            if (!isReadyToSend()) {
                return Boolean.FALSE;
            }

            ensureMessageId(record);
            String key = extractContractId(record);
            String data = typeManager.writeValueAsString(record);
            producer.send(new ProducerRecord<>(topic, key, data)).get();
            return Boolean.TRUE;
        } catch (EdcException e) {
            monitor.severe("Failed to serialize or send telemetry record to Kafka: " + e.getMessage());
            return Boolean.FALSE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            monitor.severe("Telemetry record sending interrupted: " + e.getMessage());
            return Boolean.FALSE;
        } catch (ExecutionException e) {
            handleKafkaSendException(e);
            return Boolean.FALSE;
        } catch (RuntimeException e) {
            monitor.severe("Unexpected error while sending telemetry record: " + e.getMessage());
            return Boolean.FALSE;
        }
    }

    private boolean isReadyToSend() {
        if (closed.get()) {
            monitor.warning("Attempted to send record on closed Kafka publisher");
            return false;
        }
        if (topic == null || topic.isBlank()) {
            monitor.warning("Invalid topic provided to Kafka publisher");
            return false;
        }
        if (producer == null) {
            monitor.warning("Producer not available in Kafka publisher");
            return false;
        }
        return true;
    }

    private void ensureMessageId(TelemetryRecord record) {
        Object messageId = record.getProperties().get("messageId");
        if (messageId == null) {
            record.getProperties().put("messageId", UUID.randomUUID().toString());
        }
    }

    private String extractContractId(TelemetryRecord record) {
        Object contractId = record.getProperties().get("contractId");
        if (contractId instanceof String) {
            return String.valueOf(contractId);
        } else if (contractId != null) {
            monitor.warning("contractId is not a String, ignoring it");
        }
        return null;
    }

    private void handleKafkaSendException(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RecordTooLargeException) {
            monitor.warning("Failed to send telemetry record to Kafka: RecordTooLarge");
        } else {
            monitor.severe("Failed to send telemetry record to Kafka: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // The producer is shared and managed by the Extension. We do not close it here.
            monitor.debug("Kafka telemetry publisher client closed");
        }
    }
}

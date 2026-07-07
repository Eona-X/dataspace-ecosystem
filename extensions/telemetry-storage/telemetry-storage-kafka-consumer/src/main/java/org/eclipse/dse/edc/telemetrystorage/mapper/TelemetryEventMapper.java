package org.eclipse.dse.edc.telemetrystorage.mapper;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEvent;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

public final class TelemetryEventMapper {

    public TelemetryEvent mapToEvent(TelemetryRecord record) {
        Map<String, Object> properties = record.getProperties();

        String messageId = getStringProperty(properties, "messageId", null);
        if (messageId == null) {
            throw new IllegalArgumentException("Missing required field 'messageId' — cannot guarantee idempotence");
        }

        String contractId = getStringProperty(properties, "contractId", null);
        if (contractId == null) {
            throw new IllegalArgumentException("Missing required field 'contractId' — billing data would be corrupted");
        }

        String participantId = getStringProperty(properties, "participantId", null);
        if (participantId == null) {
            throw new IllegalArgumentException(
                    "Missing required field 'participantId' — billing data would be corrupted");
        }

        int statusCode = getIntProperty(properties, "responseStatusCode", 0);
        int responseSize = getIntProperty(properties, "responseSize", 0);

        try {
            Timestamp timestamp = parseTimestamp(properties);
            return new TelemetryEvent(messageId, contractId, participantId, statusCode, responseSize, null, timestamp);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Failed to map TelemetryRecord for messageId=" + messageId + ": " + e.getMessage(), e);
        }
    }

    private Timestamp parseTimestamp(Map<String, Object> properties) {
        if (!properties.containsKey("timestamp")) {
            throw new IllegalArgumentException("Missing required field 'timestamp'");
        }
        Object value = properties.get("timestamp");
        if (value instanceof Number number) {
            return new Timestamp(number.longValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Timestamp.from(Instant.parse(stringValue));
            } catch (DateTimeParseException e) {
                try {
                    return Timestamp.valueOf(stringValue);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "Invalid timestamp format '" + stringValue + "' for messageId=" + properties.get("messageId"), ex);
                }
            }
        }
        throw new IllegalArgumentException(
                "Unsupported timestamp type '" + value.getClass().getName() + "' for messageId=" + properties.get("messageId"));
    }

    private static String getStringProperty(Map<String, Object> properties, String key, String defaultValue) {
        Object value = properties.get(key);
        return (value instanceof String) ? (String) value : defaultValue;
    }

    private static int getIntProperty(Map<String, Object> properties, String key, int defaultValue) {
        Object value = properties.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}

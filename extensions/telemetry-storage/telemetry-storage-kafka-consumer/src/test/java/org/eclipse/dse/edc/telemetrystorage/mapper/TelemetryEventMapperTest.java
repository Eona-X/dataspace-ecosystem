package org.eclipse.dse.edc.telemetrystorage.mapper;

import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecord;
import org.eclipse.dse.spi.telemetrystorage.TelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryEventMapperTest {

    private static final String CONTRACT_ID = "contractId";
    private static final String PARTICIPANT_ID = "participantId";
    private static final String MESSAGE_ID = "messageId";
    private static final String RESPONSE_STATUS_CODE = "responseStatusCode";
    private static final String MSG_SIZE = "responseSize";
    private static final String TIMESTAMP = "timestamp";
    private static final String TEST_CONTRACT = "test-contract";
    private static final String TEST_PARTICIPANT = "test-participant";
    private static final String TEST_MESSAGE_ID = UUID.randomUUID().toString();
    private static final String TEST_TYPE = "test-type";

    private TelemetryEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TelemetryEventMapper();
    }

    @Test
    void shouldMapValidRecord() {
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.id()).isEqualTo(TEST_MESSAGE_ID);
        assertThat(event.contractId()).isEqualTo(TEST_CONTRACT);
        assertThat(event.participantId()).isEqualTo(TEST_PARTICIPANT);
        assertThat(event.responseStatusCode()).isEqualTo(200);
        assertThat(event.responseSize()).isEqualTo(4096);
        assertThat(event.csvId()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void shouldThrowWhenMessageIdMissing() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TEST_TYPE)
                .property(CONTRACT_ID, TEST_CONTRACT)
                .property(PARTICIPANT_ID, TEST_PARTICIPANT)
                .property(TIMESTAMP, System.currentTimeMillis())
                .build();

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field 'messageId'");
    }

    @Test
    void shouldThrowWhenContractIdMissing() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TEST_TYPE)
                .property(MESSAGE_ID, TEST_MESSAGE_ID)
                .property(PARTICIPANT_ID, TEST_PARTICIPANT)
                .property(TIMESTAMP, System.currentTimeMillis())
                .build();

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field 'contractId'");
    }

    @Test
    void shouldThrowWhenParticipantIdMissing() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TEST_TYPE)
                .property(MESSAGE_ID, TEST_MESSAGE_ID)
                .property(CONTRACT_ID, TEST_CONTRACT)
                .property(TIMESTAMP, System.currentTimeMillis())
                .build();

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field 'participantId'");
    }

    @Test
    void shouldThrowWhenTimestampIsMissing() {
        TelemetryRecord record = TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TEST_TYPE)
                .property(MESSAGE_ID, TEST_MESSAGE_ID)
                .property(CONTRACT_ID, TEST_CONTRACT)
                .property(PARTICIPANT_ID, TEST_PARTICIPANT)
                .build();

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field 'timestamp'");
    }

    @Test
    void shouldParseNumericTimestamp() {
        long expectedTimestamp = 1700000000000L;
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(TIMESTAMP, expectedTimestamp);

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.timestamp().getTime()).isEqualTo(expectedTimestamp);
    }

    @Test
    void shouldParseSqlStringTimestamp() {
        String sqlTimestamp = "2024-01-01 10:00:00";
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(TIMESTAMP, sqlTimestamp);

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp().toString()).contains("2024-01-01");
    }

    @Test
    void shouldParseIsoTimestamp() {
        String isoDate = "2024-01-01T10:00:00Z";
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(TIMESTAMP, isoDate);

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.timestamp().toInstant()).isEqualTo(Instant.parse(isoDate));
    }

    @Test
    void shouldThrowWhenTimestampIsInvalidString() {
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(TIMESTAMP, "not-a-valid-timestamp");

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid timestamp format");
    }

    @Test
    void shouldThrowWhenTimestampTypeIsUnsupported() {
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(TIMESTAMP, true);

        assertThatThrownBy(() -> mapper.mapToEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported timestamp type");
    }

    @Test
    void shouldFallbackToZeroWhenStatusCodeIsInvalidString() {
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(RESPONSE_STATUS_CODE, "invalid-int");

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.responseStatusCode()).isZero();
    }

    @Test
    void shouldParseIntegersFromStrings() {
        TelemetryRecord record = buildRecord(TEST_MESSAGE_ID, TEST_CONTRACT, TEST_PARTICIPANT);
        record.getProperties().put(RESPONSE_STATUS_CODE, "201");
        record.getProperties().put(MSG_SIZE, "1024");

        TelemetryEvent event = mapper.mapToEvent(record);

        assertThat(event.responseStatusCode()).isEqualTo(201);
        assertThat(event.responseSize()).isEqualTo(1024);
    }

    private TelemetryRecord buildRecord(String messageId, String contractId, String participantId) {
        return TelemetryRecord.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .type(TEST_TYPE)
                .property(MESSAGE_ID, messageId)
                .property(CONTRACT_ID, contractId)
                .property(PARTICIPANT_ID, participantId)
                .property(RESPONSE_STATUS_CODE, 200)
                .property(MSG_SIZE, 4096)
                .property(TIMESTAMP, System.currentTimeMillis())
                .build();
    }
}

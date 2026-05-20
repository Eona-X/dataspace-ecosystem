package org.eclipse.edc.dse.telemetry.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TelemetryEventTest {

    private TelemetryEvent event;
    private ParticipantId participant;
    private Report report;

    @BeforeEach
    void setUp() {
        event = new TelemetryEvent();
        participant = Mockito.mock(ParticipantId.class);
        report = Mockito.mock(Report.class);
    }

    @Test
    @DisplayName("Should be correct when setters and getters are called")
    void shouldBeCorrect_WhenSettersAndGettersAreCalled() {
        String id = "event123";
        String contractId = "contract456";
        int statusCode = 200;
        int msgSize = 1024;
        LocalDateTime timestamp = LocalDateTime.now();

        event.setId(id);
        event.setContractId(contractId);
        event.setParticipant(participant);
        event.setResponseStatus(statusCode);
        event.setMsgSize(msgSize);
        event.setCsvReport(report);
        event.setTimestamp(timestamp);

        assertEquals(id, event.getId());
        assertEquals(contractId, event.getContractId());
        assertEquals(participant, event.getParticipant());
        assertEquals(statusCode, event.getResponseStatus());
        assertEquals(msgSize, event.getMsgSize());
        assertEquals(report, event.getCsvReport());
        assertEquals(timestamp, event.getTimestamp());
    }

    @Test
    @DisplayName("Should correctly implement equals and hashCode")
    void shouldBeCorrect_WhenEqualsAndHashcodeAreUsed() {
        LocalDateTime timestamp = LocalDateTime.now();

        TelemetryEvent event1 = new TelemetryEvent();
        event1.setId("id1");
        event1.setContractId("contract1");
        event1.setParticipant(participant);
        event1.setResponseStatus(200);
        event1.setMsgSize(500);
        event1.setCsvReport(report);
        event1.setTimestamp(timestamp);

        TelemetryEvent event2 = new TelemetryEvent();
        event2.setId("id1");
        event2.setContractId("contract1");
        event2.setParticipant(participant);
        event2.setResponseStatus(200);
        event2.setMsgSize(500);
        event2.setCsvReport(report);
        event2.setTimestamp(timestamp);

        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when IDs are different")
    void shouldNotBeEquals_WhenIdsAreDifferent() {
        TelemetryEvent event1 = new TelemetryEvent();
        event1.setId("id1");

        TelemetryEvent event2 = new TelemetryEvent();
        event2.setId("id2");

        assertNotEquals(event1, event2);
    }

    @Test
    @DisplayName("Should not be equal when compared with null")
    void shouldNotBeNull_WhenComparedWithNull() {
        assertNotEquals(null, event);
    }

    @Test
    @DisplayName("Should not be equal when compared with different class")
    void shouldNotBeEquals_WhenComparedWithDifferentClass() {
        assertNotEquals("some string", event);
    }
}
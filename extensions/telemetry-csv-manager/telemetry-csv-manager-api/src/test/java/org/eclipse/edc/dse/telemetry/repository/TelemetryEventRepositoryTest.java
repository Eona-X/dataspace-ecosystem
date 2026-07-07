package org.eclipse.edc.dse.telemetry.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.eclipse.edc.dse.telemetry.model.ParticipantId;
import org.eclipse.edc.dse.telemetry.model.TelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.eclipse.edc.dse.telemetry.TestUtils.TEST_PERSISTENCE_UNIT;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TelemetryEventRepositoryTest {

    private static EntityManager em;
    private static EntityManagerFactory emf;

    private TelemetryEventRepository telemetryEventRepository;
    private ParticipantRepository participantRepository;

    private ParticipantId consumer;
    private ParticipantId provider;

    @BeforeAll
    void setup() {
        emf = Persistence.createEntityManagerFactory(TEST_PERSISTENCE_UNIT);
        em = emf.createEntityManager();

        telemetryEventRepository = new TelemetryEventRepository(em);
        participantRepository = new ParticipantRepository(em);

        consumer = new ParticipantId("p1", "test@example.com", "consumer");
        participantRepository.saveTransactional(consumer);
        provider = new ParticipantId("p2", "test2@example.com", "provider");
        participantRepository.saveTransactional(provider);
    }

    private TelemetryEvent createTelemetryEvent(String id, String contractId, ParticipantId participantId, int statusCode, int msgSize, LocalDateTime timestamp) {
        TelemetryEvent event = new TelemetryEvent();
        event.setId(id);
        event.setContractId(contractId);
        event.setParticipant(participantId);
        event.setResponseStatus(statusCode);
        event.setMsgSize(msgSize);
        event.setTimestamp(timestamp);
        return event;
    }

    @AfterEach
    void tearDown() {
        telemetryEventRepository.findAll().forEach(telemetryEventRepository::deleteTransactional);
    }

    @Test
    @DisplayName("Retrieval of all telemetry should return all events")
    void shouldReturnAllEvents_WhenFindAll() {
        TelemetryEvent event1 = createTelemetryEvent("e1", "contract-1", consumer, 500,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));

        TelemetryEvent event2 = createTelemetryEvent("e2", "contract-1", provider, 500,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));

        telemetryEventRepository.saveTransactional(event1);
        telemetryEventRepository.saveTransactional(event2);

        List<TelemetryEvent> telemetryEventList = telemetryEventRepository.findAll();
        assertThat(telemetryEventList.size()).isEqualTo(2);
        assertThat(telemetryEventList.get(0)).isEqualTo(event1);
        assertThat(telemetryEventList.get(1)).isEqualTo(event2);
    }

    @Test
    @DisplayName("Should return correct events when filtering by month and year")
    void shouldReturnCorrectEvents_WhenFilteringByMonthAndYear() {
        TelemetryEvent targetEvent = createTelemetryEvent("e1", "contract-1", consumer, 500,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));

        TelemetryEvent eventWithSameMonthButDifferentYear = createTelemetryEvent("e2", "contract-1", consumer, 500,
                254, LocalDateTime.of(2024, 11, 15, 12, 0));

        TelemetryEvent eventWithSameYearAndMonthButDifferentParticipant = createTelemetryEvent("e3", "contract-1", provider, 500,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));

        TelemetryEvent eventWithSameYearButDifferentMonth = createTelemetryEvent("e4", "contract-1", consumer, 500,
                254, LocalDateTime.of(2025, 12, 15, 12, 0));

        telemetryEventRepository.saveTransactional(targetEvent);
        telemetryEventRepository.saveTransactional(eventWithSameMonthButDifferentYear);
        telemetryEventRepository.saveTransactional(eventWithSameYearAndMonthButDifferentParticipant);
        telemetryEventRepository.saveTransactional(eventWithSameYearButDifferentMonth);

        List<TelemetryEvent> contractParties = telemetryEventRepository.findByParticipantIdForMonth(consumer.getId(), 11, 2025);

        assertThat(contractParties.size()).isEqualTo(1);
        assertThat(contractParties.get(0)).isEqualTo(targetEvent);
    }

    @Test
    @DisplayName("Should return empty when filtering by month and year and no match exists")
    void shouldReturnEmpty_WhenFilteringByMonthAndYearAndNoMatchExists() {
        TelemetryEvent e1 = createTelemetryEvent("e1", "contract-1", consumer, 500,
                254, LocalDateTime.of(2024, 11, 15, 12, 0));

        TelemetryEvent e2 = createTelemetryEvent("e2", "contract-1", provider, 500,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));

        TelemetryEvent e3 = createTelemetryEvent("e3", "contract-1", consumer, 500,
                254, LocalDateTime.of(2025, 12, 15, 12, 0));

        telemetryEventRepository.saveTransactional(e1);
        telemetryEventRepository.saveTransactional(e2);
        telemetryEventRepository.saveTransactional(e3);

        List<TelemetryEvent> contractParties = telemetryEventRepository.findByParticipantIdForMonth(consumer.getId(), 11, 2026);

        assertThat(contractParties.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Retrieval of all telemetry should return empty list when no events exist")
    void shouldReturnEmptyList_WhenNoEventsExist() {
        List<TelemetryEvent> telemetryEventList = telemetryEventRepository.findAll();
        assertThat(telemetryEventList.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Retrieval of contract stats should find status grouped by contract id and status code when exists")
    void shouldFindStatusGroupedByContractIdAndStatusCode_WhenExists() {
        TelemetryEvent event1 = createTelemetryEvent("e1", "contract-1", consumer, 200,
                254, LocalDateTime.of(2025, 11, 15, 12, 0));
        TelemetryEvent event2 = createTelemetryEvent("e2", "contract-1", provider, 500,
                300, LocalDateTime.of(2025, 11, 15, 12, 0));
        TelemetryEvent event3 = createTelemetryEvent("e3", "contract-2", consumer, 200,
                150, LocalDateTime.of(2025, 11, 19, 12, 0));

        telemetryEventRepository.saveTransactional(event1);
        telemetryEventRepository.saveTransactional(event2);
        telemetryEventRepository.saveTransactional(event3);

        List<ContractStats> result = telemetryEventRepository.findStatsGroupedByContractIdAndStatusCode(consumer.getId(), 11, 2025);

        assertThat(result.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Retrieval of contract stats should return empty list when no events exist")
    void shouldReturnEmptyStats_WhenNoEventsExist() {
        List<ContractStats> result = telemetryEventRepository.findStatsGroupedByContractIdAndStatusCode(consumer.getId(), 11, 2025);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("findStatsForParticipants should return empty list when participantIds is null")
    void findStatsForParticipants_shouldReturnEmpty_WhenNull() {
        List<ContractStats> result = telemetryEventRepository.findStatsForParticipants(null, 11, 2025);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("findStatsForParticipants should return empty list when participantIds is empty")
    void findStatsForParticipants_shouldReturnEmpty_WhenEmpty() {
        List<ContractStats> result = telemetryEventRepository.findStatsForParticipants(List.of(), 11, 2025);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("findStatsForParticipants should return stats for a single participant")
    void findStatsForParticipants_shouldReturnStats_ForSingleParticipant() {
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fs1", "contract-1", consumer, 200, 100, LocalDateTime.of(2025, 11, 10, 10, 0)));
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fs2", "contract-1", consumer, 200, 150, LocalDateTime.of(2025, 11, 12, 10, 0)));

        List<ContractStats> result = telemetryEventRepository.findStatsForParticipants(List.of(consumer.getId()), 11, 2025);

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).contractId()).isEqualTo("contract-1");
        assertThat(result.get(0).participantId()).isEqualTo(consumer.getId());
        assertThat(result.get(0).responseStatus()).isEqualTo(200);
        assertThat(result.get(0).msgSize()).isEqualTo(250L);
        assertThat(result.get(0).eventCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("findStatsForParticipants should return stats for multiple participants")
    void findStatsForParticipants_shouldReturnStats_ForMultipleParticipants() {
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fm1", "contract-1", consumer, 200, 100, LocalDateTime.of(2025, 11, 10, 10, 0)));
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fm2", "contract-1", provider, 200, 200, LocalDateTime.of(2025, 11, 10, 11, 0)));

        List<ContractStats> result = telemetryEventRepository.findStatsForParticipants(
                List.of(consumer.getId(), provider.getId()), 11, 2025);

        assertThat(result.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("findParticipantsByContractIds should return empty list when contractIds is null")
    void findParticipantsByContractIds_shouldReturnEmpty_WhenNull() {
        List<ContractParticipant> result = telemetryEventRepository.findParticipantsByContractIds(null);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("findParticipantsByContractIds should return empty list when contractIds is empty")
    void findParticipantsByContractIds_shouldReturnEmpty_WhenEmpty() {
        List<ContractParticipant> result = telemetryEventRepository.findParticipantsByContractIds(List.of());
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("findParticipantsByContractIds should return participant for a single contract")
    void findParticipantsByContractIds_shouldReturnParticipant_ForSingleContract() {
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fp1", "contract-A", consumer, 200, 100, LocalDateTime.of(2025, 11, 10, 10, 0)));

        List<ContractParticipant> result = telemetryEventRepository.findParticipantsByContractIds(List.of("contract-A"));

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).contractId()).isEqualTo("contract-A");
        assertThat(result.get(0).participant()).isEqualTo(consumer);
    }

    @Test
    @DisplayName("findParticipantsByContractIds should return participants for multiple contracts")
    void findParticipantsByContractIds_shouldReturnParticipants_ForMultipleContracts() {
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fpm1", "contract-A", consumer, 200, 100, LocalDateTime.of(2025, 11, 10, 10, 0)));
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fpm2", "contract-B", provider, 200, 200, LocalDateTime.of(2025, 11, 10, 11, 0)));

        List<ContractParticipant> result = telemetryEventRepository.findParticipantsByContractIds(
                List.of("contract-A", "contract-B"));

        assertThat(result.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("findParticipantsByContractIds should return empty for non-existent contract")
    void findParticipantsByContractIds_shouldReturnEmpty_ForNonExistentContract() {
        telemetryEventRepository.saveTransactional(
                createTelemetryEvent("fpn1", "contract-A", consumer, 200, 100, LocalDateTime.of(2025, 11, 10, 10, 0)));

        List<ContractParticipant> result = telemetryEventRepository.findParticipantsByContractIds(List.of("non-existent"));

        assertThat(result.size()).isEqualTo(0);
    }
}

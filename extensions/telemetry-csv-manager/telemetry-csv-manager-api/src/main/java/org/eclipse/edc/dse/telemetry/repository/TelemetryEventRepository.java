package org.eclipse.edc.dse.telemetry.repository;

import jakarta.persistence.EntityManager;
import org.eclipse.edc.dse.telemetry.model.TelemetryEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TelemetryEventRepository extends GenericRepository<TelemetryEvent> {

    private static final int IN_CLAUSE_BATCH_SIZE = 500;

    public TelemetryEventRepository(EntityManager em) {
        super(em, TelemetryEvent.class);
    }

    public List<TelemetryEvent> findByParticipantIdForMonth(String participantId, int month, int year) {
        DateRange range = getMonthRange(month, year);
        return em.createQuery(
                        "SELECT te FROM TelemetryEvent te " +
                                "WHERE te.participantId = :participantId " +
                                "AND te.timestamp >= :startDate AND te.timestamp < :endDate", TelemetryEvent.class)
                .setParameter("participantId", participantId)
                .setParameter("startDate", range.startDate)
                .setParameter("endDate", range.endDate)
                .getResultList();
    }

    public List<ContractStats> findStatsGroupedByContractIdAndStatusCode(String participantId, int month, int year) {
        DateRange range = getMonthRange(month, year);
        return em.createQuery(
                "SELECT new org.eclipse.edc.dse.telemetry.repository.ContractStats(te.contractId, te.responseStatus, SUM(te.msgSize), COUNT(te)) " +
                        "FROM TelemetryEvent te " +
                        "WHERE te.participantId = :participantId " +
                        "AND te.timestamp >= :startDate AND te.timestamp < :endDate " +
                        "GROUP BY te.contractId, te.responseStatus", ContractStats.class)
                .setParameter("participantId", participantId)
                .setParameter("startDate", range.startDate)
                .setParameter("endDate", range.endDate)
                .getResultList();
    }

    public List<ContractParticipant> findParticipantsByContractIds(List<String> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return List.of();
        }

        List<ContractParticipant> results = new ArrayList<>();
        for (int i = 0; i < contractIds.size(); i += IN_CLAUSE_BATCH_SIZE) {
            List<String> batch = contractIds.subList(i, Math.min(i + IN_CLAUSE_BATCH_SIZE, contractIds.size()));
            List<ContractParticipant> batchResults = em.createQuery(
                    "SELECT new org.eclipse.edc.dse.telemetry.repository.ContractParticipant(te.contractId, p) " +
                            "FROM TelemetryEvent te JOIN te.participant p " +
                            "WHERE te.contractId IN :contractIds", ContractParticipant.class)
                    .setParameter("contractIds", batch)
                    .getResultList();
            results.addAll(batchResults);
        }
        return results;
    }

    public List<ContractStats> findStatsForParticipants(List<String> participantIds, int month, int year) {
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }
        DateRange range = getMonthRange(month, year);
        List<ContractStats> results = new ArrayList<>();
        for (int i = 0; i < participantIds.size(); i += IN_CLAUSE_BATCH_SIZE) {
            List<String> batch = participantIds.subList(i, Math.min(i + IN_CLAUSE_BATCH_SIZE, participantIds.size()));
            List<ContractStats> batchResults = em.createQuery(
                    "SELECT new org.eclipse.edc.dse.telemetry.repository.ContractStats(te.participantId, te.contractId, te.responseStatus, SUM(te.msgSize), COUNT(te)) " +
                            "FROM TelemetryEvent te " +
                            "WHERE te.participantId IN :participantIds " +
                            "AND te.timestamp >= :startDate AND te.timestamp < :endDate " +
                            "GROUP BY te.contractId, te.participantId, te.responseStatus", ContractStats.class)
                    .setParameter("participantIds", batch)
                    .setParameter("startDate", range.startDate)
                    .setParameter("endDate", range.endDate)
                    .getResultList();
            results.addAll(batchResults);
        }
        return results;
    }

    private DateRange getMonthRange(int month, int year) {
        LocalDateTime startDate = LocalDate.of(year, month, 1).atStartOfDay();
        LocalDateTime endDate = startDate.plusMonths(1);
        return new DateRange(startDate, endDate);
    }

    private record DateRange(LocalDateTime startDate, LocalDateTime endDate) {}
}

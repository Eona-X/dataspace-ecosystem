package org.eclipse.edc.dse.telemetry.services.report;

import org.eclipse.edc.dse.telemetry.model.ParticipantId;
import org.eclipse.edc.dse.telemetry.model.Report;
import org.eclipse.edc.dse.telemetry.model.TelemetryEvent;
import org.eclipse.edc.dse.telemetry.repository.ContractParticipant;
import org.eclipse.edc.dse.telemetry.repository.ContractStats;
import org.eclipse.edc.dse.telemetry.repository.ParticipantRepository;
import org.eclipse.edc.dse.telemetry.repository.ReportRepository;
import org.eclipse.edc.dse.telemetry.repository.TelemetryEventRepository;
import org.eclipse.edc.dse.telemetry.services.ReportUtil;
import org.eclipse.edc.dse.telemetry.services.storage.ReportStorageService;
import org.eclipse.edc.dse.telemetry.services.storage.StorageException;
import org.eclipse.edc.spi.monitor.Monitor;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.eclipse.edc.dse.telemetry.services.ReportUtil.getObjectPath;
import static org.eclipse.edc.dse.telemetry.services.ReportUtil.getValue;

public class ReportGenerationService {

    private static final String COUNTERPARTY_NOT_AVAILABLE = "N/A";
    private static final int EXPECTED_CONTRACT_PARTIES = 2;
    private static final String DEFAULT_SIZE_VALUE = "0";
    private static final long DEFAULT_EVENT_COUNT = 0L;
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(500);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final double RETRY_BACKOFF_FACTOR = 2.0;

    private final ParticipantRepository participantRepository;
    private final ReportRepository reportRepository;
    private final TelemetryEventRepository telemetryEventRepository;
    private final ReportStorageService storageService;
    private final Monitor monitor;
    private final Clock clock;
    private final RetryPolicy<String> uploadRetryPolicy;

    public ReportGenerationService(Monitor monitor,
                                   ParticipantRepository participantRepository,
                                   ReportRepository reportRepository,
                                   TelemetryEventRepository telemetryEventRepository,
                                   ReportStorageService storageService,
                                   Clock clock) {
        this.monitor = monitor;
        this.participantRepository = participantRepository;
        this.reportRepository = reportRepository;
        this.telemetryEventRepository = telemetryEventRepository;
        this.storageService = storageService;
        this.clock = clock;

        this.uploadRetryPolicy = RetryPolicy.<String>builder()
                .handle(StorageException.class)
                .withBackoff(INITIAL_RETRY_DELAY, MAX_RETRY_DELAY, RETRY_BACKOFF_FACTOR)
                .withMaxRetries(MAX_RETRIES)
                .onRetry(e -> monitor.warning("Upload retry attempt " + e.getAttemptCount() + " failed: " + e.getLastException().getMessage()))
                .build();
    }

    void generatePreviousMonthReportForAllParticipants() {
        List<ParticipantId> participants = participantRepository.findAll();
        LocalDateTime oneMonthBeforeDate = LocalDateTime.now(clock).minusMonths(1);
        for (ParticipantId participant : participants) {
            try {
                generateReport(participant, oneMonthBeforeDate, false);
                generateReport(participant, oneMonthBeforeDate, true);
            } catch (Exception e) {
                monitor.severe("Failed to generate report for participant "
                        + participant.getName() + ", continuing with next.", e);
            }
        }
    }

    public void generateParticipantReport(String participantName, LocalDateTime targetDateTime, boolean generateCounterpartyReport) {
        ParticipantId participant = findParticipant(participantName);
        if (participant == null) {
            this.monitor.severe("Participant not found: " + participantName);
            throw new RuntimeException("Participant not found: " + participantName);
        }

        generateReport(participant, targetDateTime, false);
        if (generateCounterpartyReport) {
            generateReport(participant, targetDateTime, true);
        }
    }

    public ParticipantId findParticipant(String participantName) {
        return participantRepository.findByParticipantName(participantName);
    }

    public void generateReport(ParticipantId participant, LocalDateTime targetDateTime, boolean includeCounterpartyInfo) {
        monitor.debug("Generating report for participant " + participant.getName());
        int month = targetDateTime.getMonthValue();
        int year = targetDateTime.getYear();

        String fileName = ReportUtil.generateReportFileName(participant.getName(), targetDateTime, includeCounterpartyInfo);
        String path = getObjectPath(targetDateTime, fileName, includeCounterpartyInfo);

        try {
            String csvContent = buildCsvContent(participant, month, year, includeCounterpartyInfo);
            String objectUrl = uploadReport(path, csvContent);
            saveReportMetadata(participant, targetDateTime, fileName, objectUrl, month, year);
            monitor.info("Report finalized for participant: " + participant.getName());
        } catch (StorageException e) {
            monitor.severe("Failed to upload report " + fileName + " to remote storage", e);
            throw e;
        }
    }

    private String buildCsvContent(ParticipantId participant, int month, int year, boolean includeCounterpartyInfo) {
        List<ContractStats> contractStats = telemetryEventRepository.findStatsGroupedByContractIdAndStatusCode(participant.getId(), month, year);
        List<String> csvLines = collectCsvEntryInfo(participant, contractStats, month, year, includeCounterpartyInfo);
        return ReportUtil.generateCsvReportContent(csvLines, includeCounterpartyInfo);
    }

    private String uploadReport(String path, String csvContent) {
        monitor.debug("Uploading report to path " + path);
        String objectUrl = uploadWithRetry(path, csvContent.getBytes(StandardCharsets.UTF_8));
        monitor.debug("Report uploaded to " + objectUrl);
        return objectUrl;
    }

    private void saveReportMetadata(ParticipantId participant, LocalDateTime targetDateTime, String fileName, String objectUrl, int month, int year) {
        Report report = new Report(fileName, objectUrl, participant);
        report.setTimestamp(targetDateTime);

        List<TelemetryEvent> events = telemetryEventRepository.findByParticipantIdForMonth(participant.getId(), month, year);
        report.setTelemetryEvents(events);

        reportRepository.saveTransactional(report);
    }

    private String uploadWithRetry(String path, byte[] data) {
        try {
            return Failsafe.with(uploadRetryPolicy)
                    .get(() -> storageService.upload(path, data));
        } catch (StorageException e) {
            monitor.severe("Failed to upload report after " + MAX_RETRIES + " attempts: " + path, e);
            throw e;
        }
    }

    private List<String> collectCsvEntryInfo(ParticipantId participant, List<ContractStats> contractStats, int month, int year, boolean includeCounterpartyInfo) {
        monitor.debug(() -> String.format("Building report for participant %s %s counterparty info", participant.getName(), includeCounterpartyInfo ? "with" : "without"));

        Map<String, List<ParticipantId>> participantsByContractId = fetchParticipantsMap(contractStats);

        return includeCounterpartyInfo
                ? buildExtendedReportCsv(participant, contractStats, participantsByContractId, month, year)
                : buildReportCsv(participant, contractStats, participantsByContractId);
    }

    private List<String> buildExtendedReportCsv(ParticipantId participant, List<ContractStats> contractStats,
                                                Map<String, List<ParticipantId>> participantsByContractId, int month, int year) {

        Map<String, CounterpartyInfo> counterpartyInfoByContractId =
                resolveCounterpartyInfo(participant, contractStats, participantsByContractId);

        Map<StatsKey, ContractStats> counterpartyStatsIndex =
                resolveCounterpartyStats(counterpartyInfoByContractId, month, year);

        return buildExtendedCsvLines(participant, contractStats, counterpartyInfoByContractId, counterpartyStatsIndex);
    }

    private Map<String, CounterpartyInfo> resolveCounterpartyInfo(ParticipantId participant,
                                                                  List<ContractStats> contractStats,
                                                                  Map<String, List<ParticipantId>> participantsByContractId) {
        return contractStats.stream()
                .collect(Collectors.toMap(
                        ContractStats::contractId,
                        cs -> extractCounterpartyInfo(
                                participant,
                                participantsByContractId.getOrDefault(cs.contractId(), List.of()),
                                cs.contractId()),
                        (existing, duplicate) -> existing
                ));
    }

    private Map<StatsKey, ContractStats> resolveCounterpartyStats(Map<String, CounterpartyInfo> counterpartyInfoByContractId,
                                                                  int month, int year) {
        Set<String> counterpartyIds = counterpartyInfoByContractId.values().stream()
                .map(CounterpartyInfo::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return buildCounterpartyStatsIndex(counterpartyIds, month, year);
    }

    private List<String> buildExtendedCsvLines(ParticipantId participant,
                                               List<ContractStats> contractStats,
                                               Map<String, CounterpartyInfo> counterpartyInfoByContractId,
                                               Map<StatsKey, ContractStats> counterpartyStatsIndex) {
        List<String> csvLines = new ArrayList<>(contractStats.size());
        for (ContractStats contractStat : contractStats) {
            String contractId = contractStat.contractId();
            CounterpartyInfo counterpartyInfo = counterpartyInfoByContractId.get(contractId);

            StatsKey statsKey = new StatsKey(counterpartyInfo.id(), contractId, contractStat.responseStatus());
            ContractStats counterPartyContractStats = counterpartyStatsIndex.getOrDefault(
                    statsKey, createEmptyStats(contractId));

            csvLines.add(buildExtendedCsvEntryRow(
                    contractStat, participant.getName(), counterpartyInfo.name(), counterPartyContractStats));
        }
        return csvLines;
    }

    private List<String> buildReportCsv(ParticipantId participant, List<ContractStats> contractStats,
                                        Map<String, List<ParticipantId>> participantsByContractId) {
        List<String> csvLines = new ArrayList<>(contractStats.size());

        for (ContractStats contractStat : contractStats) {
            String contractId = contractStat.contractId();
            List<ParticipantId> contractParties = participantsByContractId.getOrDefault(contractId, List.of());

            CounterpartyInfo counterpartyInfo = extractCounterpartyInfo(participant, contractParties, contractId);
            csvLines.add(buildCsvEntryRow(contractStat, counterpartyInfo.name()));
        }

        return csvLines;
    }

    private Map<String, List<ParticipantId>> fetchParticipantsMap(List<ContractStats> contractStats) {
        List<String> contractIds = contractStats.stream()
                .map(ContractStats::contractId)
                .distinct()
                .collect(Collectors.toList());

        List<ContractParticipant> results = telemetryEventRepository.findParticipantsByContractIds(contractIds);

        return results.stream()
                .distinct()
                .collect(Collectors.groupingBy(
                        ContractParticipant::contractId,
                        Collectors.mapping(ContractParticipant::participant, Collectors.toList())
                ));
    }

    private CounterpartyInfo extractCounterpartyInfo(ParticipantId participant, List<ParticipantId> contractParties, String contractId) {
        if (contractParties.size() != EXPECTED_CONTRACT_PARTIES) {
            monitor.warning(() -> String.format("Contract %s does not have exactly %d parties, found: %d", contractId, EXPECTED_CONTRACT_PARTIES, contractParties.size()));
            return new CounterpartyInfo(null, COUNTERPARTY_NOT_AVAILABLE);
        }

        ParticipantId counterparty = findCounterparty(participant, contractParties);
        return new CounterpartyInfo(counterparty.getId(), counterparty.getName());
    }

    private ParticipantId findCounterparty(ParticipantId participant, List<ParticipantId> contractParties) {
        return participant.getId().equals(contractParties.get(0).getId())
                ? contractParties.get(1)
                : contractParties.get(0);
    }

    private Map<StatsKey, ContractStats> buildCounterpartyStatsIndex(Set<String> counterpartyIds, int month, int year) {
        if (counterpartyIds.isEmpty()) {
            return Map.of();
        }

        List<ContractStats> allStats = telemetryEventRepository.findStatsForParticipants(
                new ArrayList<>(counterpartyIds), month, year);

        Map<StatsKey, ContractStats> index = new HashMap<>(allStats.size());
        for (ContractStats stats : allStats) {
            StatsKey key = new StatsKey(stats.participantId(), stats.contractId(), stats.responseStatus());
            index.put(key, stats);
        }
        return index;
    }

    private record StatsKey(String participantId, String contractId, Integer statusCode) {}

    private ContractStats createEmptyStats(String contractId) {
        return new ContractStats(contractId, null, null, null);
    }


    private static String buildExtendedCsvEntryRow(ContractStats contractStat, String participantName, String counterpartyName, ContractStats counterPartyContractStats) {
        return String.join(",",
                getValue(contractStat.contractId()),
                getValue(contractStat.responseStatus()),
                getValue(participantName),
                getValue(counterpartyName),
                getMsgSizeValue(contractStat),
                getMsgSizeValue(counterPartyContractStats),
                getEventCountValue(contractStat),
                getEventCountValue(counterPartyContractStats)
        );
    }

    private static String buildCsvEntryRow(ContractStats contractStat, String counterpartyName) {
        return String.join(",",
                getValue(contractStat.contractId()),
                getValue(counterpartyName),
                getValue(contractStat.responseStatus()),
                getMsgSizeValue(contractStat),
                getEventCountValue(contractStat)
        );
    }

    private static String getMsgSizeValue(ContractStats contractStat) {
        return contractStat.msgSize() != null
                ? bytesToKilobytesRoundedString(contractStat.msgSize())
                : DEFAULT_SIZE_VALUE;
    }

    private static String getEventCountValue(ContractStats contractStat) {
        return String.valueOf(contractStat.eventCount() != null ? contractStat.eventCount() : DEFAULT_EVENT_COUNT);
    }

    /**
     * Converts bytes to kilobytes and returns a string rounded to 2 decimal places.
     *
     * @param bytes the number of bytes to convert
     * @return a string representation of kilobytes rounded to 2 decimal places
     */
    public static String bytesToKilobytesRoundedString(long bytes) {
        double kb = bytes / 1024.0;
        return String.format("%.2f", kb);
    }
}

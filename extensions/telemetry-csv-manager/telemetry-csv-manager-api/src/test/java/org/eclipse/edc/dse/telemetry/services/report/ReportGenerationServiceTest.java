package org.eclipse.edc.dse.telemetry.services.report;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.eclipse.edc.dse.telemetry.model.ParticipantId;
import org.eclipse.edc.dse.telemetry.model.Report;
import org.eclipse.edc.dse.telemetry.model.TelemetryEvent;
import org.eclipse.edc.dse.telemetry.repository.ParticipantRepository;
import org.eclipse.edc.dse.telemetry.repository.ReportRepository;
import org.eclipse.edc.dse.telemetry.repository.TelemetryEventRepository;
import org.eclipse.edc.dse.telemetry.services.ReportUtil;
import org.eclipse.edc.dse.telemetry.services.storage.AzureStorageService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.eclipse.edc.dse.telemetry.TestUtils.P1_DID;
import static org.eclipse.edc.dse.telemetry.TestUtils.P2_DID;
import static org.eclipse.edc.dse.telemetry.TestUtils.PARTICIPANT_NAME;
import static org.eclipse.edc.dse.telemetry.TestUtils.PARTICIPANT_NAME_2;
import static org.eclipse.edc.dse.telemetry.TestUtils.TEST_PERSISTENCE_UNIT;
import static org.eclipse.edc.dse.telemetry.TestUtils.USER_EMAIL;
import static org.eclipse.edc.dse.telemetry.TestUtils.USER_EMAIL_2;
import static org.eclipse.edc.dse.telemetry.services.ReportUtil.EXTENDED_REPORT_HEADER;
import static org.eclipse.edc.dse.telemetry.services.ReportUtil.REPORT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportGenerationServiceTest {

    public static final String CONTRACT_1 = "contract1";
    private static final String CONTRACT_2 = "contract2";
    private static final String CONTRACT_3 = "contract3";
    private ReportRepository reportRepository;
    private ParticipantRepository participantRepo;
    private TelemetryEventRepository telemetryEventRepo;
    private static EntityManager em;
    private static EntityManagerFactory emf;


    @BeforeAll
    void setup() {
        emf = Persistence.createEntityManagerFactory(TEST_PERSISTENCE_UNIT);
        em = emf.createEntityManager();

        reportRepository = new ReportRepository(em);
        participantRepo = new ParticipantRepository(em);
        telemetryEventRepo = new TelemetryEventRepository(em);

    }

    @AfterEach
    void tearDown() {
        // Reports depend on participants so reports should be deleted first to break the dependencies
        em.getTransaction().begin();
        reportRepository.findAll().forEach(reportRepository::delete);
        telemetryEventRepo.findAll().forEach(telemetryEventRepo::delete);
        participantRepo.findAll().forEach(participantRepo::delete);
        em.getTransaction().commit();
    }

    @Test
    @DisplayName("Should find participant when saved")
    void shouldFindParticipant_WhenSaved() {
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        participantRepo.saveTransactional(participant1);

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);

        ParticipantId participant = reportGenerationService.findParticipant(PARTICIPANT_NAME);
        assertNotNull(participant);
        assertEquals(P1_DID, participant.getId());
        assertEquals(USER_EMAIL, participant.getEmail());
        assertEquals(PARTICIPANT_NAME, participant.getName());
    }

    @Test
    @DisplayName("Should succeed when extended report generation is performed")
    void shouldSucceed_WhenExtendedReportGeneration() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(true)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(true))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), true);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 1);
            List<String> expectedCsv = List.of(EXTENDED_REPORT_HEADER, "contract1,200,participantName,participantName2,0.16,0.16,1,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation is performed")
    void shouldSucceed_WhenReportGeneration() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(false)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(false))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), false);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 1);
            List<String> expectedCsv = List.of(REPORT_HEADER, "contract1,participantName2,200,0.16,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when extended report generation with multiple contracts is performed")
    void shouldSucceed_WhenExtendedReportGenerationWithMultipleContracts() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 2), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 6), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant2, LocalDateTime.of(2025, Month.AUGUST, 14, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 7), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant2, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(true)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(true))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), true);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 4);
            List<String> expectedCsv = List.of(EXTENDED_REPORT_HEADER,
                    "contract1,200,participantName,participantName2,0.19,0.19,2,2",
                    "contract2,200,participantName,participantName2,0.16,0.16,1,1",
                    "contract3,200,participantName,participantName2,0.49,0.49,1,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation with multiple contracts is performed")
    void shouldSucceed_WhenReportGenerationWithMultipleContracts() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 2), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 6), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant2, LocalDateTime.of(2025, Month.AUGUST, 14, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 7), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant2, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(false)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(false))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), false);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 4);
            List<String> expectedCsv = List.of(REPORT_HEADER,
                    "contract1,participantName2,200,0.19,2",
                    "contract2,participantName2,200,0.16,1",
                    "contract3,participantName2,200,0.49,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation with multiple contracts, multiple response status codes is performed")
    void shouldSucceed_WhenExtendedReportGenerationWithMultipleContractsAndMultipleStatusCodes() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 17, 2), 159, 400));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 2), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 6), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 22, 2), 159, 400));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant2, LocalDateTime.of(2025, Month.AUGUST, 14, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 7), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 7), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 8), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 10), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant2, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(true)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(true))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), true);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 8);
            List<String> expectedCsv = List.of(EXTENDED_REPORT_HEADER,
                    "contract1,200,participantName,participantName2,0.04,0.04,1,1",
                    "contract2,200,participantName,participantName2,0.16,0.16,1,1",
                    "contract2,400,participantName,participantName2,0.16,0,1,0",
                    "contract3,200,participantName,participantName2,0.98,0.49,2,1",
                    "contract3,500,participantName,participantName2,1.46,0,3,0");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation with multiple contracts, multiple response status codes is performed")
    void shouldSucceed_WhenReportGenerationWithMultipleContractsAndMultipleStatusCodes() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 17, 2), 159, 400));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 2), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 21, 6), 40, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant1, LocalDateTime.of(2025, Month.AUGUST, 15, 22, 2), 159, 400));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_2, participant2, LocalDateTime.of(2025, Month.AUGUST, 14, 13, 2), 159, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 7), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 7), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 8), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 8, 10), 500, 500));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant1, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_3, participant2, LocalDateTime.of(2025, Month.AUGUST, 2, 18, 2), 500, 200));
        em.getTransaction().commit();

        Monitor mockedMonitor = mock(Monitor.class);
        doNothing().when(mockedMonitor).severe(any(String.class));
        doNothing().when(mockedMonitor).info(any(String.class));

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(false)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(false))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), false);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 8);
            List<String> expectedCsv = List.of(REPORT_HEADER,
                    "contract1,participantName2,200,0.04,1",
                    "contract2,participantName2,200,0.16,1",
                    "contract2,participantName2,400,0.16,1",
                    "contract3,participantName2,200,0.98,2",
                    "contract3,participantName2,500,1.46,3");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation is performed but there is a discrepancy in message size")
    void shouldSucceed_WhenExtendedReportGenerationButDiscrepancyInMsgSize() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 160, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        Monitor mockedMonitor = mock(Monitor.class);
        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);

        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(true)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(true))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), true);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 1);
            List<String> expectedCsv = List.of(EXTENDED_REPORT_HEADER,
                    "contract1,200,participantName,participantName2,0.16,0.16,1,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation is performed but there is a discrepancy in message size")
    void shouldSucceed_WhenReportGenerationButDiscrepancyInMsgSize() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 160, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        Monitor mockedMonitor = mock(Monitor.class);
        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);

        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(false)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(false))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), false);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 1);
            List<String> expectedCsv = List.of(REPORT_HEADER,
                    "contract1,participantName2,200,0.16,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when extended report generation is performed but there is a discrepancy in event count with counterparty info")
    void shouldSucceed_WhenExtendedReportGenerationButDiscrepancyInEventCount() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 160, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 16, 0), 50, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        Monitor mockedMonitor = mock(Monitor.class);
        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(true)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(true))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), true);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 2);
            List<String> expectedCsv = List.of(EXTENDED_REPORT_HEADER,
                    "contract1,200,participantName,participantName2,0.21,0.16,2,1");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    @Test
    @DisplayName("Should succeed when report generation is performed but there is a discrepancy in event count")
    void shouldSucceed_WhenReportGenerationButDiscrepancyInEventCount() {
        em.getTransaction().begin();
        ParticipantId participant1 = new ParticipantId(P1_DID, USER_EMAIL, PARTICIPANT_NAME);
        ParticipantId participant2 = new ParticipantId(P2_DID, USER_EMAIL_2, PARTICIPANT_NAME_2);
        participantRepo.save(participant1);
        participantRepo.save(participant2);

        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), 160, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 16, 0), 50, 200));
        telemetryEventRepo.save(createTelemetryEvent(CONTRACT_1, participant2, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 2), 159, 200));
        em.getTransaction().commit();

        AzureStorageService mockedAzureStorageService = mock(AzureStorageService.class);
        doAnswer(c -> "objectUrl").when(mockedAzureStorageService).upload(any(), any());

        Monitor mockedMonitor = mock(Monitor.class);
        ReportGenerationService reportGenerationService = new ReportGenerationService(mockedMonitor, participantRepo, reportRepository, telemetryEventRepo, mockedAzureStorageService);
        try (MockedStatic<ReportUtil> mockedStatic = Mockito.mockStatic(ReportUtil.class)) {
            AtomicReference<String> capturedContent = new AtomicReference<>();
            mockedStatic.when(() -> ReportUtil.generateCsvReportContent(any(), eq(false)))
                    .thenAnswer(invocation -> {
                        String result = (String) invocation.callRealMethod();
                        capturedContent.set(result);
                        return result;
                    });
            mockedStatic.when(() -> ReportUtil.generateReportFileName(any(), any(), eq(false))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Integer.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(Long.class))).then(InvocationOnMock::callRealMethod);
            mockedStatic.when(() -> ReportUtil.getValue(any(String.class))).then(InvocationOnMock::callRealMethod);

            reportGenerationService.generateReport(participant1, LocalDateTime.of(2025, Month.AUGUST, 23, 12, 0), false);

            assertEquals(1, reportRepository.findAll().size());
            Report report = reportRepository.findAll().get(0);
            validateReport(report, participant1, 2);
            List<String> expectedCsv = List.of(REPORT_HEADER,
                    "contract1,participantName2,200,0.21,2");
            assertLinesMatch(expectedCsv, capturedContent.get().lines().toList());
        }
    }

    private static void validateReport(Report report, ParticipantId participant1, int expected) {
        assertEquals("objectUrl", report.getCsvLink());
        assertEquals(participant1, report.getParticipant());
        assertEquals(expected, report.getTelemetryEvents().size());
    }

    private static TelemetryEvent createTelemetryEvent(String contractId, ParticipantId participant, LocalDateTime timestamp, Integer msgSize, Integer responseStatusCode) {
        TelemetryEvent telemetryEvent1 = new TelemetryEvent();
        telemetryEvent1.setId(UUID.randomUUID().toString());
        telemetryEvent1.setContractId(contractId);
        telemetryEvent1.setParticipant(participant);
        telemetryEvent1.setResponseStatusCode(responseStatusCode);
        telemetryEvent1.setMsgSize(msgSize);
        telemetryEvent1.setCsvReport(null);
        telemetryEvent1.setTimestamp(timestamp);
        return telemetryEvent1;
    }

    @AfterAll
    static void teardown() {
        if (em != null && em.isOpen()) em.close();
        if (emf != null && emf.isOpen()) emf.close();
    }
}
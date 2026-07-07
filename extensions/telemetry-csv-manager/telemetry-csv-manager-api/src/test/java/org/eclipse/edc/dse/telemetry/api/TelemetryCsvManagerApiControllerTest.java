package org.eclipse.edc.dse.telemetry.api;

import jakarta.ws.rs.core.Response;
import org.eclipse.edc.dse.telemetry.services.report.ReportGeneratorScheduler;
import org.eclipse.edc.dse.telemetry.services.report.ReportGeneratorSchedulerExtension;
import org.eclipse.edc.dse.telemetry.services.storage.ReportStorageService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryCsvManagerApiControllerTest {

    private static final String PARTICIPANT_NAME = "MyCompany";
    private static final int VALID_MONTH = 10;
    private static final int VALID_YEAR = 2023;
    private static final String CSV_CONTENT = "contract_id,counterparty_name\ncontract1,Partner";

    @Mock
    private Monitor monitor;

    @Mock
    private ReportGeneratorScheduler mockScheduler;

    @Mock
    private ReportStorageService mockStorageService;

    private TelemetryCsvManagerApiController controller;

    @BeforeEach
    void setUp() {
        ReportGeneratorSchedulerExtension.scheduler = mockScheduler;
        ReportGeneratorSchedulerExtension.storageService = mockStorageService;
        controller = new TelemetryCsvManagerApiController(monitor);
    }

    @AfterEach
    void tearDown() {
        ReportGeneratorSchedulerExtension.scheduler = null;
        ReportGeneratorSchedulerExtension.storageService = null;
    }

    @Test
    void getReportShouldReturn401WhenAuthorizationHeaderMissing() {
        Response response = controller.getReport(null, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(monitor).warning(anyString());
    }

    @Test
    void getReportShouldReturn401WhenAuthorizationHeaderMalformed() {
        Response response = controller.getReport("Basic token", VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void getReportShouldReturn401WhenJwtHasInvalidStructure() {
        String invalidJwt = "Bearer header.payload";
        Response response = controller.getReport(invalidJwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void getReportShouldReturn500WhenJwtMissingRolesArray() {
        String jwt = buildJwtWithPayload("{\"some\":\"value\"}");
        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(500);
        verify(monitor).severe(contains("Unexpected error during report retrieval"), any());
    }

    @Test
    void getReportShouldReturn401WhenJwtRolesArrayEmpty() {
        String jwt = buildJwtWithPayload("{\"roles\":[]}");
        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(monitor).warning(anyString());
    }

    @Test
    void getReportShouldReturn403WhenJwtHasNoParticipantRole() {
        String jwt = buildJwtWithPayload("{\"roles\":[\"Admin\"]}");
        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(403);
        verify(monitor).warning(anyString());
    }

    @Test
    void getReportShouldReturn403WhenJwtHasMultipleParticipantRoles() {
        String jwt = buildJwtWithPayload("{\"roles\":[\"Participant.Company1\",\"Participant.Company2\"]}");
        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(403);
        verify(monitor).warning(contains("Unexpected number of participant roles"));
    }

    @Test
    void getReportShouldReturn403WhenParticipantNotFoundInScheduler() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        when(mockScheduler.checkParticipantExists(PARTICIPANT_NAME)).thenReturn(false);
        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(403);
        verify(mockScheduler).checkParticipantExists(PARTICIPANT_NAME);
    }

    @Test
    void getReportShouldReturn400WhenMonthIsNull() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        Response response = controller.getReport(jwt, null, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void getReportShouldReturn400WhenMonthIsLessThanOne() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        Response response = controller.getReport(jwt, 0, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void getReportShouldReturn400WhenMonthIsGreaterThanTwelve() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        Response response = controller.getReport(jwt, 13, VALID_YEAR);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void getReportShouldReturn200WithCsvBodyWhenReportFoundInStorage() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        when(mockScheduler.checkParticipantExists(PARTICIPANT_NAME)).thenReturn(true);
        when(mockStorageService.download(anyString()))
                .thenReturn(new ByteArrayInputStream(CSV_CONTENT.getBytes()));

        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(CSV_CONTENT.getBytes());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStorageService).download(pathCaptor.capture());
        assertThat(pathCaptor.getValue())
                .contains(PARTICIPANT_NAME)
                .contains(String.valueOf(VALID_YEAR))
                .contains(String.format("%02d", VALID_MONTH));
    }

    @Test
    void getReportShouldReturn404WhenReportNotFoundInStorage() {
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        when(mockScheduler.checkParticipantExists(PARTICIPANT_NAME)).thenReturn(true);
        when(mockStorageService.download(anyString()))
                .thenThrow(new RuntimeException("Not found"));

        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(monitor).warning(contains("No report found"));
    }

    @Test
    void getReportShouldReturn404WhenStorageServiceIsNull() {
        ReportGeneratorSchedulerExtension.storageService = null;
        String jwt = buildJwtWithRole("Participant." + PARTICIPANT_NAME);
        when(mockScheduler.checkParticipantExists(PARTICIPANT_NAME)).thenReturn(true);

        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(monitor).severe(contains("Storage service not initialized"));
    }

    @Test
    void getReportShouldExtractFirstSegmentOfParticipantNameWhenRoleNameContainsDots() {
        String complexName = "My.Company.Inc";
        String jwt = buildJwtWithRole("Participant." + complexName);
        when(mockScheduler.checkParticipantExists("My")).thenReturn(true);
        when(mockStorageService.download(anyString()))
                .thenReturn(new ByteArrayInputStream(CSV_CONTENT.getBytes()));

        Response response = controller.getReport(jwt, VALID_MONTH, VALID_YEAR);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(mockScheduler).checkParticipantExists("My");
    }

    @Test
    void generateReportShouldReturn400WhenParticipantNameIsNull() {
        ReportGenerationRequest request = new ReportGenerationRequest(null, VALID_YEAR, VALID_MONTH, true);
        Response response = controller.generateReport(request);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void generateReportShouldReturn400WhenParticipantNameIsEmpty() {
        ReportGenerationRequest request = new ReportGenerationRequest("", VALID_YEAR, VALID_MONTH, true);
        Response response = controller.generateReport(request);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void generateReportShouldDefaultToTrueWhenGenerateCounterpartyReportIsNull() {
        ReportGenerationRequest request = new ReportGenerationRequest(PARTICIPANT_NAME, VALID_YEAR, VALID_MONTH, null);
        Response response = controller.generateReport(request);

        assertThat(response.getStatus()).isEqualTo(201);
        ArgumentCaptor<LocalDateTime> dateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mockScheduler).triggerGenerationForParticipant(
                eq(PARTICIPANT_NAME),
                dateCaptor.capture(),
                eq(true)
        );
        LocalDateTime expectedDate = LocalDateTime.of(VALID_YEAR, VALID_MONTH, 1, 0, 0);
        assertThat(dateCaptor.getValue()).isEqualTo(expectedDate);
    }

    @Test
    void generateReportShouldReturn201AndTriggerSchedulerWhenRequestIsValid() {
        ReportGenerationRequest request = new ReportGenerationRequest(PARTICIPANT_NAME, VALID_YEAR, VALID_MONTH, true);
        Response response = controller.generateReport(request);

        assertThat(response.getStatus()).isEqualTo(201);
        ArgumentCaptor<LocalDateTime> dateCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mockScheduler).triggerGenerationForParticipant(
                eq(PARTICIPANT_NAME),
                dateCaptor.capture(),
                eq(true)
        );
        LocalDateTime expectedDate = LocalDateTime.of(VALID_YEAR, VALID_MONTH, 1, 0, 0);
        assertThat(dateCaptor.getValue()).isEqualTo(expectedDate);
    }

    @Test
    void generateReportShouldReturn500WhenSchedulerThrowsException() {
        doThrow(new RuntimeException("Scheduler error"))
                .when(mockScheduler).triggerGenerationForParticipant(anyString(), any(LocalDateTime.class), anyBoolean());

        ReportGenerationRequest request = new ReportGenerationRequest(PARTICIPANT_NAME, VALID_YEAR, VALID_MONTH, true);
        Response response = controller.generateReport(request);

        assertThat(response.getStatus()).isEqualTo(500);
        verify(monitor).severe(contains("Unexpected error during report generation"), any());
    }

    private String buildJwtWithPayload(String jsonPayload) {
        String encodedPayload = Base64.getEncoder().withoutPadding().encodeToString(jsonPayload.getBytes());
        return "Bearer header." + encodedPayload + ".signature";
    }

    private String buildJwtWithRole(String role) {
        return buildJwtWithPayload("{\"roles\":[\"" + role + "\"]}");
    }
}

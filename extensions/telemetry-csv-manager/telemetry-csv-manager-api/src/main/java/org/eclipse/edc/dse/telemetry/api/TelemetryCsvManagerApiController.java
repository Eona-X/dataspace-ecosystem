package org.eclipse.edc.dse.telemetry.api;

import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.dse.telemetry.services.ReportUtil;
import org.eclipse.edc.spi.monitor.Monitor;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.StreamSupport;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.eclipse.edc.dse.telemetry.services.report.ReportGeneratorSchedulerExtension.azureStorageService;
import static org.eclipse.edc.dse.telemetry.services.report.ReportGeneratorSchedulerExtension.scheduler;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/billing-reports")
public class TelemetryCsvManagerApiController implements TelemetryCsvManagerApi {

    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Monitor monitor;

    public TelemetryCsvManagerApiController(Monitor monitor) {
        this.monitor = monitor;
        monitor.info("Telemetry CSV Manager API Controller initialized");
    }

    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("text/csv")
    public Response getReport(@HeaderParam("Authorization") String authHeader, @QueryParam("month") Integer month, @QueryParam("year") Integer year) {
        monitor.info("Fetching report...");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            monitor.warning("Authorization header missing or malformed");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Missing JWT token").build();
        }

        if (checksInvalidPeriod(month)) {
            monitor.warning("Invalid month provided: " + month);
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date range provided").build();
        }

        String jwtToken = authHeader.split(" ")[1]; // Removes Bearer part from header
        monitor.debug("JWT token received");

        String[] jwtParts = jwtToken.split("\\.");
        if (jwtParts.length != 3) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid JWT token").build();
        }

        try {
            List<String> roles = extractRolesFromJwt(jwtParts);

            if (roles.isEmpty()) {
                this.monitor.warning("Roles array in JWT is empty");
                return Response.status(Response.Status.UNAUTHORIZED).entity("No roles contained in the JWT token").build();
            }

            List<String> participantRoles = roles.stream().filter(r -> r.startsWith("Participant.")).toList();
            if (participantRoles.size() != 1) {
                this.monitor.warning("Unexpected number of participant roles in JWT: " + participantRoles.size());
                return Response.status(Response.Status.FORBIDDEN).entity("Unexpected number of participant roles in JWT: " + participantRoles.size()).build();
            }

            String[] roleParts = participantRoles.get(0).split("\\.");
            if (roleParts.length < 2 || roleParts[1] == null || roleParts[1].isEmpty()) {
                this.monitor.warning("Unexpected number of roles in JWT: " + roles.size());
                return Response.status(Response.Status.FORBIDDEN).entity("Missing or invalid participant in roles").build();
            }
            String participantName = roleParts[1];

            boolean participantExists = scheduler.checkParticipantExists(participantName);
            if (!participantExists) {
                this.monitor.warning("Participant not found: " + participantName);
                return Response.status(Response.Status.FORBIDDEN).entity("Participant does not exist").build();
            }

            LocalDateTime dateTime = LocalDateTime.of(year, month, 1, 0, 0);
            String reportFilename = ReportUtil.generateReportFileName(participantName, dateTime, false);
            String objectPath = ReportUtil.getObjectPath(dateTime, reportFilename, false);
            byte[] csvData = getReportFromRemoteStorage(objectPath);
            if (csvData == null) {
                this.monitor.warning("No report found at path: " + objectPath + " for participant " + participantName + " month " + month + " year " + year);
                return Response.status(Response.Status.NOT_FOUND).entity("No report found for specified period").build();
            } else {
                this.monitor.info("Report successfully retrieved for participant: " + participantName);
                return Response.ok(csvData)
                        .type("text/csv")
                        .header("Content-Disposition", "attachment; filename=\"" + reportFilename + "\"")
                        .build();
            }
        } catch (JwtException e) {
            this.monitor.severe("JWT parsing failed: " + e.getMessage(), e);
            return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid JWT: " + e.getMessage()).build();
        } catch (Exception e) {
            this.monitor.severe("Unexpected error during report retrieval", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Failed to retrieve report due to an unexpected error.").build();
        }
    }

    private static List<String> extractRolesFromJwt(String[] jwtParts) throws JsonProcessingException {
        String decodedJwtPayload = new String(DECODER.decode(jwtParts[1]));
        JsonNode root = MAPPER.readTree(decodedJwtPayload);
        JsonNode rolesNode = root.get("roles");
        if (rolesNode == null || !rolesNode.isArray()) {
            throw new IllegalArgumentException("Missing roles array in JWT");
        }

        return StreamSupport.stream(rolesNode.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static boolean checksInvalidPeriod(Integer month) {
        return month == null || month < 1 || month > 12;
    }

    // This api will not be exposed via APIM, it is meant to be used only internally, therefore we hide it from the OpenAPI documentation
    @Operation(hidden = true)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/json")
    public Response generateReport(ReportGenerationRequest reportGenerationRequest) {
        String participantName = reportGenerationRequest.participantName();
        Integer year = reportGenerationRequest.year();
        Integer month = reportGenerationRequest.month();
        Boolean generateCounterpartyReport = reportGenerationRequest.generateCounterpartyReport();

        this.monitor.info("Generating report for participant " + participantName + " month " + month + " year " + year);
        if (participantName == null || participantName.isEmpty()) {
            this.monitor.warning("Invalid participant name provided: " + participantName);
            return Response.status(Response.Status.BAD_REQUEST).entity("Participant name not provided").build();
        }

        if (generateCounterpartyReport == null) {
            this.monitor.warning("Counterparty report generation not requested or invalid");
            return Response.status(Response.Status.BAD_REQUEST).entity("Counterparty report generation not requested or invalid").build();
        }

        if (checksInvalidPeriod(month)) {
            monitor.warning("Invalid month provided: " + month);
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date range provided").build();
        }

        try {
            scheduler.triggerGenerationForParticipant(participantName, LocalDateTime.of(year, month, 1, 0, 0), generateCounterpartyReport);
            return Response.status(Response.Status.CREATED).build();
        } catch (BlobStorageException e) {
            if (e.getErrorCode() == BlobErrorCode.BLOB_ALREADY_EXISTS) {
                String conflictMessage = "This report already exists, participant: " +
                        reportGenerationRequest.participantName() + " month: " + reportGenerationRequest.month() +
                        " year: " + reportGenerationRequest.year();
                this.monitor.warning(conflictMessage);
                return Response.status(Response.Status.CONFLICT).entity(conflictMessage).build();
            }
            monitor.severe("BlobStorageException occurred", e);
            throw e;
        } catch (Exception e) {
            this.monitor.severe("Unexpected error during report generation", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Generation failed").build();
        }
    }

    private byte[] getReportFromRemoteStorage(String objectPath) {
        monitor.debug("Attempting to download report from storage at path: " + objectPath);
        try (InputStream downloadedInputStream = azureStorageService.download(objectPath)) {
            return downloadedInputStream.readAllBytes();
        } catch (Exception e) {
            this.monitor.warning("Exception thrown while getting report: " + e);
            return null;
        }
    }
}

package org.eclipse.edc.dse.telemetry.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "telemetry_event",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_contract_participant_timestamp",
                columnNames = {"contract_id", "participant_did", "timestamp"}
        )
)
public class TelemetryEvent {

    @Id
    @Column(nullable = false, length = 255)
    private String id;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "participant_did", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "telemetry_event_participant_did_fk"))
    private ParticipantId participant;

    @Column(name = "response_status_code", nullable = false)
    private int responseStatus;

    @Column(name = "msg_size", nullable = false)
    private int msgSize;

    @Column(name = "participant_did", insertable = false, updatable = false)
    private String participantId;

    @ManyToOne
    @JoinColumn(name = "csv_id", referencedColumnName = "id",
            foreignKey = @ForeignKey(name = "telemetry_event_csv_id_fk"))
    private Report csvReport;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    public TelemetryEvent() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public ParticipantId getParticipant() {
        return participant;
    }

    public void setParticipant(ParticipantId participant) {
        this.participant = participant;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(int responseStatus) {
        this.responseStatus = responseStatus;
    }

    public int getMsgSize() {
        return msgSize;
    }

    public void setMsgSize(int msgSize) {
        this.msgSize = msgSize;
    }

    public String getParticipantId() {
        return participantId;
    }

    public Report getCsvReport() {
        return csvReport;
    }

    public void setCsvReport(Report csvReport) {
        this.csvReport = csvReport;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TelemetryEvent that = (TelemetryEvent) o;
        return responseStatus == that.responseStatus && msgSize == that.msgSize && Objects.equals(id, that.id) &&
                Objects.equals(contractId, that.contractId) && Objects.equals(participant, that.participant) &&
                Objects.equals(csvReport, that.csvReport) && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, contractId, participant, responseStatus, msgSize, csvReport, timestamp);
    }
}

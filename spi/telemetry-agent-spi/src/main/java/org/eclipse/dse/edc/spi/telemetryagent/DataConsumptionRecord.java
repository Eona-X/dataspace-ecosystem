package org.eclipse.dse.edc.spi.telemetryagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Map;
import java.util.Objects;

@JsonDeserialize(builder = DataConsumptionRecord.Builder.class)
public class DataConsumptionRecord extends TelemetryRecord {

    private static final String TYPE = "DataConsumption";
    private static final String PROPERTY_RESPONSE_SIZE = "responseSize";
    private static final String PROPERTY_CONTRACT_ID = "contractId";
    private static final String PROPERTY_PARTICIPANT_ID = "participantId";
    private static final String PROPERTY_RESPONSE_STATUS_CODE = "responseStatusCode";
    private static final String PROPERTY_TIMESTAMP = "timestamp";

    private DataConsumptionRecord() {
        super();
    }

    public Long getResponseSize() {
        return getProperty(PROPERTY_RESPONSE_SIZE);
    }

    public String getContractId() {
        return getPropertyAsString(PROPERTY_CONTRACT_ID);
    }

    public Integer getResponseStatusCode() {
        return getProperty(PROPERTY_RESPONSE_STATUS_CODE);
    }

    public String getParticipantId() {
        return getPropertyAsString(PROPERTY_PARTICIPANT_ID);
    }

    public Long getTimestamp() {
        return getProperty(PROPERTY_TIMESTAMP);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder extends TelemetryRecord.Builder {

        private Builder() {
            super(new DataConsumptionRecord());
        }

        @Override
        public Builder self() {
            return this;
        }

        public Builder responseSize(Long responseSize) {
            this.property(PROPERTY_RESPONSE_SIZE, responseSize);
            return this;
        }

        public Builder contractId(String contractId) {
            this.property(PROPERTY_CONTRACT_ID, contractId);
            return this;
        }

        public Builder participantId(String participantId) {
            this.property(PROPERTY_PARTICIPANT_ID, participantId);
            return this;
        }

        public Builder responseStatusCode(Integer responseStatusCode) {
            this.property(PROPERTY_RESPONSE_STATUS_CODE, responseStatusCode);
            return this;
        }

        public Builder timestamp(Long timestamp) {
            this.property(PROPERTY_TIMESTAMP, timestamp);
            return this;
        }

        @Override
        public Builder traceContext(Map<String, String> traceContext) {
            super.traceContext(traceContext);
            return this;
        }

        @Override
        public DataConsumptionRecord build() {
            this.type(TYPE);
            super.build();
            var record = (DataConsumptionRecord) entity;
            assertNotNull(record.getContractId(), PROPERTY_CONTRACT_ID);
            assertNotNull(record.getResponseSize(), PROPERTY_RESPONSE_SIZE);
            assertNotNull(record.getResponseStatusCode(), PROPERTY_RESPONSE_STATUS_CODE);
            assertNotNull(record.getParticipantId(), PROPERTY_PARTICIPANT_ID);
            assertNotNull(record.getTimestamp(), PROPERTY_TIMESTAMP);
            return record;
        }

        private static void assertNotNull(Object o, String property) {
            Objects.requireNonNull(o, "Missing '%s' in DataConsumptionRecord".formatted(property));
        }

        @JsonCreator
        public static Builder newInstance() {
            return new Builder();
        }

    }

}


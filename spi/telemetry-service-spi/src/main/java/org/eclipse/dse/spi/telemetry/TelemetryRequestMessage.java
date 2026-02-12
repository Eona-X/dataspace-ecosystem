package org.eclipse.dse.spi.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.eclipse.edc.spi.types.domain.message.RemoteMessage;

import java.util.Objects;

public class TelemetryRequestMessage implements RemoteMessage {

    private String protocol;
    private String counterPartyAddress;
    private String counterPartyId;

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public String getCounterPartyAddress() {
        return counterPartyAddress;
    }

    @Override
    public String getCounterPartyId() {
        return counterPartyId;
    }

    public static class Builder {
        private final TelemetryRequestMessage message;

        private Builder() {
            this.message = new TelemetryRequestMessage();
        }

        @JsonCreator
        public static Builder newInstance() {
            return new Builder();
        }

        public Builder protocol(String protocol) {
            this.message.protocol = protocol;
            return this;
        }

        public Builder counterPartyAddress(String counterPartyAddress) {
            this.message.counterPartyAddress = counterPartyAddress;
            return this;
        }

        public Builder counterPartyId(String counterPartyId) {
            this.message.counterPartyId = counterPartyId;
            return this;
        }

        public TelemetryRequestMessage build() {
            Objects.requireNonNull(message.protocol, "protocol cannot be null");
            return message;
        }
    }
}

package org.eclipse.edc.dse.dataplane.billing;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import org.eclipse.dse.edc.spi.telemetryagent.DataConsumptionRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.telemetry.Telemetry;
import org.eclipse.edc.web.spi.exception.InvalidRequestException;

import java.util.Optional;

public class DataConsumptionMetricsPublisher implements ContainerResponseFilter {

    protected static final String CONTRACT_ID_HEADER = "Contract-Id";
    protected static final String TRACE_PARENT_HEADER = "traceparent";

    private final TelemetryRecordStore telemetryRecordStore;
    private final Monitor monitor;
    private final Telemetry telemetry;
    private final String ownDid;

    DataConsumptionMetricsPublisher(TelemetryRecordStore telemetryRecordStore, Monitor monitor, Telemetry telemetry, String ownDid) {
        this.telemetryRecordStore = telemetryRecordStore;
        this.monitor = monitor;
        this.telemetry = telemetry;
        this.ownDid = ownDid;
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        var entity = responseContext.getEntity();
        var responseSize = (entity != null) ? entity.toString().getBytes().length : 0L;
        var contractId = Optional.ofNullable(requestContext.getHeaderString(CONTRACT_ID_HEADER))
                .orElseThrow(() -> new InvalidRequestException("Missing '%s' header in request".formatted(CONTRACT_ID_HEADER)));
        var traceContext = telemetry.getCurrentTraceContext();
        var traceParent = traceContext.get(TRACE_PARENT_HEADER);

        monitor.debug("[TCX: " + traceParent + "][BillingDataStoreFilter] Data request response size: " + responseSize + " bytes");

        var record = DataConsumptionRecord.Builder.newInstance()
                .contractId(contractId)
                .responseSize(responseSize)
                .responseStatusCode(responseContext.getStatus())
                .participantId(ownDid)
                .traceContext(traceContext)
                .timestamp(System.currentTimeMillis())
                .build();

        telemetryRecordStore.save(record);
    }

}

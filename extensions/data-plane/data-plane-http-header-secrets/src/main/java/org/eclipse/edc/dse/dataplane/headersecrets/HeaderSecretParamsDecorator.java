package org.eclipse.edc.dse.dataplane.headersecrets;

import org.eclipse.edc.connector.dataplane.http.spi.HttpDataAddress;
import org.eclipse.edc.connector.dataplane.http.spi.HttpParamsDecorator;
import org.eclipse.edc.connector.dataplane.http.spi.HttpRequestParams;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static java.lang.String.format;

/**
 * Decorates outgoing HTTP requests with headers resolved from the {@link Vault}, based on
 * {@code HttpDataAddress} properties prefixed with {@link #HEADER_SECRET_PREFIX}.
 * <p>
 * A property {@code header-secret:<name>} whose value is a Vault secret alias results in an HTTP header
 * named {@code <name>} whose value is the resolved secret. Multiple such properties are supported on the
 * same address. If any referenced secret cannot be resolved, no header is applied and an {@link EdcException}
 * listing every unresolved alias is thrown.
 */
public class HeaderSecretParamsDecorator implements HttpParamsDecorator {

    public static final String HEADER_SECRET_PREFIX = "header-secret:";

    private final Vault vault;
    private final Monitor monitor;

    public HeaderSecretParamsDecorator(Vault vault, Monitor monitor) {
        this.vault = vault;
        this.monitor = monitor;
    }

    @Override
    public HttpRequestParams.Builder decorate(DataFlowStartMessage request, HttpDataAddress address, HttpRequestParams.Builder params) {
        Map<String, String> resolvedHeaders = new HashMap<>();
        List<String> errors = new ArrayList<>();

        address.getProperties().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(HEADER_SECRET_PREFIX))
                .forEach(entry -> resolveHeaderSecret(entry, request, resolvedHeaders, errors));

        if (!errors.isEmpty()) {
            throw new EdcException(format(
                    "DataFlowRequest %s: failed to resolve %d header secret(s): %s",
                    request.getId(), errors.size(), String.join("; ", errors)));
        }

        resolvedHeaders.forEach(params::header);
        return params;
    }

    private void resolveHeaderSecret(Entry<String, Object> entry, DataFlowStartMessage request,
                                      Map<String, String> resolvedHeaders, List<String> errors) {
        String headerName = entry.getKey().substring(HEADER_SECRET_PREFIX.length());
        String secretAlias = String.valueOf(entry.getValue());
        String secretValue = vault.resolveSecret(secretAlias);
        if (secretValue == null) {
            String error = format("alias '%s' (header '%s') not found in vault", secretAlias, headerName);
            errors.add(error);
            monitor.severe(format("ERROR DataFlowRequest %s: %s", request.getId(), error));
        } else {
            resolvedHeaders.put(headerName, secretValue);
            monitor.debug(format("DataFlowRequest %s: header '%s' resolved from vault alias '%s'",
                    request.getId(), headerName, secretAlias));
        }
    }
}

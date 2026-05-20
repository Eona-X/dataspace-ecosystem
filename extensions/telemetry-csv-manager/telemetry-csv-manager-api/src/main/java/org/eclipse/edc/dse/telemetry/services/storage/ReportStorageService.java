package org.eclipse.edc.dse.telemetry.services.storage;

import java.io.InputStream;
import java.time.Duration;

public interface ReportStorageService extends AutoCloseable {
    String upload(String path, byte[] data);

    InputStream download(String path);

    String generatePresignedUrl(String path, Duration expiry);

    @Override
    void close() throws Exception;
}

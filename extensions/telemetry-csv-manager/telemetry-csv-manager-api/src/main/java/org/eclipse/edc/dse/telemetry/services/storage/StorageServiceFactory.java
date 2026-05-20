package org.eclipse.edc.dse.telemetry.services.storage;

import org.eclipse.edc.spi.monitor.Monitor;

public class StorageServiceFactory {

    public static final String TYPE_S3 = "s3";
    public static final String TYPE_MINIO = "minio";

    public static ReportStorageService create(Monitor monitor, String storageType, String s3Endpoint,
                                              String s3AccessKey, String s3SecretKey, String s3Bucket, String s3Region) {

        monitor.info("Object Storage Type Selected: " + storageType);

        if (TYPE_S3.equalsIgnoreCase(storageType) || TYPE_MINIO.equalsIgnoreCase(storageType)) {
            return new S3CompatibleStorageService(monitor, s3Endpoint, s3AccessKey, s3SecretKey, s3Bucket, s3Region);
        } else {
            throw new IllegalStateException("Unknown STORAGE_TYPE: " + storageType + ". Only 's3' or 'minio' are supported.");
        }
    }
}

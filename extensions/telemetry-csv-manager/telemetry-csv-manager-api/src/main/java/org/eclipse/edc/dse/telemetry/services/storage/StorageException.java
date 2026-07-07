package org.eclipse.edc.dse.telemetry.services.storage;

public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

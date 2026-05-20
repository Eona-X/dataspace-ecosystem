package org.eclipse.edc.dse.telemetry.services.report;

import org.eclipse.edc.dse.telemetry.repository.JpaUtil;
import org.eclipse.edc.dse.telemetry.services.storage.ReportStorageService;
import org.eclipse.edc.dse.telemetry.services.storage.StorageServiceFactory;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.time.Clock;

@Extension(value = "Report Generator Scheduler Extension")
public class ReportGeneratorSchedulerExtension implements ServiceExtension {

    public static final String PERSISTENCE_UNIT_NAME = "myPU";

    @Setting(description = "Datasource Default Url", key = "edc.datasource.default.url")
    public String datasourceDefaultUrl;

    @Setting(description = "Datasource Default User", key = "edc.datasource.default.user")
    public String datasourceDefaultUser;

    @Setting(description = "Datasource Default Password", key = "edc.datasource.default.password")
    public String datasourceDefaultPassword;

    @Setting(defaultValue = "s3", description = "Object Storage Type", key = "edc.storage.type")
    public String storageType;

    @Setting(description = "S3 Endpoint", key = "edc.s3.endpoint", required = false)
    public String s3Endpoint;

    @Setting(description = "S3 Access Key", key = "edc.s3.access.key", required = false)
    public String s3AccessKey;

    @Setting(description = "S3 Secret Key", key = "edc.s3.secret.key", required = false)
    public String s3SecretKey;

    @Setting(description = "S3 Bucket Name", key = "edc.s3.bucket.name", required = false)
    public String s3BucketName;

    @Setting(description = "S3 Region", key = "edc.s3.region", required = false, defaultValue = "eu-west-par")
    public String s3Region;

    @Inject
    private Monitor monitor;

    public static ReportStorageService storageService;

    // TODO: This field is accessed directly in the Controller which is not a good practice, for now we will keep it
    // but we intend to solve this problem when we implement FDPT-84292
    public static ReportGeneratorScheduler scheduler;

    @Override
    public void initialize(ServiceExtensionContext context) {
        monitor = context.getMonitor();
        monitor.info("Initializing Report Generator Scheduler Extension...");
        storageService = StorageServiceFactory.create(monitor, storageType, s3Endpoint, s3AccessKey, s3SecretKey, s3BucketName, s3Region);
        JpaUtil.init(PERSISTENCE_UNIT_NAME, datasourceDefaultUrl, datasourceDefaultUser, datasourceDefaultPassword);

        scheduler = new ReportGeneratorScheduler(monitor, storageService, Clock.systemDefaultZone());
        scheduler.start();
    }

    @Override
    public void shutdown() {
        if (scheduler != null) {
            try {
                scheduler.stop();
            } catch (Exception e) {
                monitor.warning("Error stopping scheduler during shutdown", e);
            }
        }
        if (storageService != null) {
            try {
                storageService.close();
            } catch (Exception e) {
                monitor.warning("Error closing storage service during shutdown", e);
            }
        }
        try {
            JpaUtil.shutdown();
        } catch (Exception e) {
            monitor.warning("Error shutting down JPA during shutdown", e);
        }
    }
}

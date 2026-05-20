package org.eclipse.edc.dse.telemetry.services.storage;

import org.eclipse.edc.spi.monitor.Monitor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public class S3CompatibleStorageService implements ReportStorageService {

    private static final String DEFAULT_AWS_REGION = "us-east-1";

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;
    private final Monitor monitor;

    /**
     * Production constructor: builds real S3 clients and verifies bucket existence.
     * Used by {@link StorageServiceFactory} at application startup.
     */
    public S3CompatibleStorageService(Monitor monitor, String endpoint, String accessKey, String secretKey, String bucketName, String region) {
        this(buildS3Client(endpoint, accessKey, secretKey, region),
                buildPresigner(endpoint, accessKey, secretKey, region),
                bucketName,
                monitor);
        ensureBucketExists();
    }

    /**
     * Injectable constructor: accepts pre-built clients without triggering network calls.
     * Package-private to allow unit testing with mocked S3Client.
     */
    S3CompatibleStorageService(S3Client s3Client, S3Presigner presigner, String bucketName, Monitor monitor) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucketName = bucketName;
        this.monitor = monitor;
    }

    @Override
    public String upload(String path, byte[] data) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(data));
            return s3Client.utilities().getUrl(GetUrlRequest.builder().bucket(bucketName).key(path).build()).toString();
        } catch (S3Exception e) {
            throw new StorageException("Error uploading object to S3 at path: " + path, e);
        }
    }

    @Override
    public InputStream download(String path) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            return s3Client.getObject(getObjectRequest);
        } catch (S3Exception e) {
            throw new StorageException("Error downloading object from S3 at path: " + path, e);
        }
    }

    @Override
    public String generatePresignedUrl(String path, Duration expiry) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(path)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (SdkException e) {
            throw new StorageException("Error generating presigned URL for S3 object at path: " + path, e);
        }
    }

    /**
     * Verifies and creates the bucket if needed. Package-private for testing.
     * Called automatically by the production constructor.
     */
    void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            monitor.info("Bucket " + bucketName + " already exists.");
        } catch (NoSuchBucketException e) {
            monitor.info("Bucket " + bucketName + " does not exist. Attempting to create it...");
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
                monitor.info("Bucket " + bucketName + " created successfully.");
            } catch (S3Exception ex) {
                throw new IllegalStateException("Could not create bucket " + bucketName +
                        ". Ensure the S3 credentials have the required permissions.", ex);
            }
        } catch (S3Exception e) {
            throw new IllegalStateException("Cannot verify S3 bucket '" + bucketName + "'. Check AWS permissions. Service cannot start.", e);
        }
    }

    @Override
    public void close() {
        s3Client.close();
        presigner.close();
    }

    private static S3Client buildS3Client(String endpoint, String accessKey, String secretKey, String region) {
        Region awsRegion = resolveRegion(region);
        StaticCredentialsProvider credentialsProvider = buildCredentialsProvider(accessKey, secretKey);

        S3ClientBuilder builder = S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build());

        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    private static S3Presigner buildPresigner(String endpoint, String accessKey, String secretKey, String region) {
        Region awsRegion = resolveRegion(region);
        StaticCredentialsProvider credentialsProvider = buildCredentialsProvider(accessKey, secretKey);

        S3Presigner.Builder builder = S3Presigner.builder()
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build());

        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    private static StaticCredentialsProvider buildCredentialsProvider(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static Region resolveRegion(String region) {
        return Region.of(region != null ? region : DEFAULT_AWS_REGION);
    }
}

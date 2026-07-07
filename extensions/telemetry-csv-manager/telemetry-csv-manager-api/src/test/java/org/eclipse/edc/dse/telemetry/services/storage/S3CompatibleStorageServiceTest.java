package org.eclipse.edc.dse.telemetry.services.storage;

import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3CompatibleStorageServiceTest {

    private static final String BUCKET_NAME = "test-bucket";
    private static final String FILE_PATH = "reports/2023/10/report-CompanyA-2023-10.csv";
    private static final byte[] CSV_BYTES = "header\nrow1".getBytes();
    private static final String FAKE_URL = "http://localhost:9000/test-bucket/reports/2023/10/report-CompanyA-2023-10.csv";
    private static final Duration PRESIGN_EXPIRY = Duration.ofHours(1);

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner presigner;

    @Mock
    private Monitor monitor;

    @Test
    void shouldNotCreateBucketWhenBucketAlreadyExists() {
        S3CompatibleStorageService service = buildService();

        service.ensureBucketExists();

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
        verify(monitor).info(contains("already exists"));
    }

    @Test
    void shouldCreateBucketWhenBucketDoesNotExist() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().build());
        S3CompatibleStorageService service = buildService();

        service.ensureBucketExists();

        verify(s3Client).createBucket(any(CreateBucketRequest.class));
        verify(monitor).info(contains("does not exist"));
        verify(monitor).info(contains("created successfully"));
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenBucketCreationFails() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().build());
        when(s3Client.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(S3Exception.builder().message("Access denied").build());
        S3CompatibleStorageService service = buildService();

        assertThatThrownBy(service::ensureBucketExists)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not create bucket")
                .hasMessageContaining(BUCKET_NAME);
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenHeadBucketFailsWithGenericS3Error() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().message("Internal error").build());
        S3CompatibleStorageService service = buildService();

        assertThatThrownBy(service::ensureBucketExists)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot verify S3 bucket");
    }

    @Test
    void shouldCallPutObjectWithCorrectBucketAndKeyWhenUploadingFile() {
        S3CompatibleStorageService service = buildServiceWithUploadSupport();
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        service.upload(FILE_PATH, CSV_BYTES);

        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(captor.getValue().key()).isEqualTo(FILE_PATH);
    }

    @Test
    void shouldReturnNonNullUrlWhenUploadSucceeds() {
        S3CompatibleStorageService service = buildServiceWithUploadSupport();

        String url = service.upload(FILE_PATH, CSV_BYTES);

        assertThat(url).isEqualTo(FAKE_URL);
    }

    @Test
    void shouldThrowStorageExceptionWhenUploadFailsDueToS3Error() {
        S3CompatibleStorageService service = buildService();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Upload failed").build());

        assertThatThrownBy(() -> service.upload(FILE_PATH, CSV_BYTES))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Error uploading object to S3")
                .hasMessageContaining(FILE_PATH);
    }

    @Test
    void shouldReturnInputStreamWhenDownloadSucceeds() {
        S3CompatibleStorageService service = buildService();
        ResponseInputStream<GetObjectResponse> mockStream = mock(ResponseInputStream.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockStream);

        InputStream result = service.download(FILE_PATH);

        assertThat(result).isNotNull().isSameAs(mockStream);
    }

    @Test
    void shouldCallGetObjectWithCorrectBucketAndKeyWhenDownloading() {
        S3CompatibleStorageService service = buildService();
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(mock(ResponseInputStream.class));

        service.download(FILE_PATH);

        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(captor.getValue().key()).isEqualTo(FILE_PATH);
    }

    @Test
    void shouldThrowStorageExceptionWhenDownloadFailsDueToS3Error() {
        S3CompatibleStorageService service = buildService();
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("Not found").build());

        assertThatThrownBy(() -> service.download(FILE_PATH))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Error downloading object from S3")
                .hasMessageContaining(FILE_PATH);
    }

    @Test
    void shouldReturnPresignedUrlWhenPresignerSucceeds() throws MalformedURLException {
        S3CompatibleStorageService service = buildService();
        stubPresigner(FAKE_URL);

        String url = service.generatePresignedUrl(FILE_PATH, PRESIGN_EXPIRY);

        assertThat(url).isEqualTo(FAKE_URL);
    }

    @Test
    void shouldPassExpiryDurationToPresignerWhenGeneratingPresignedUrl() throws MalformedURLException {
        S3CompatibleStorageService service = buildService();
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create(FAKE_URL).toURL());
        when(presigner.presignGetObject(captor.capture())).thenReturn(presignedRequest);

        service.generatePresignedUrl(FILE_PATH, PRESIGN_EXPIRY);

        assertThat(captor.getValue().signatureDuration()).isEqualTo(PRESIGN_EXPIRY);
    }

    @Test
    void shouldPassCorrectBucketAndKeyToPresignerWhenGeneratingPresignedUrl() throws MalformedURLException {
        S3CompatibleStorageService service = buildService();
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create(FAKE_URL).toURL());
        when(presigner.presignGetObject(captor.capture())).thenReturn(presignedRequest);

        service.generatePresignedUrl(FILE_PATH, PRESIGN_EXPIRY);

        assertThat(captor.getValue().getObjectRequest().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(captor.getValue().getObjectRequest().key()).isEqualTo(FILE_PATH);
    }

    @Test
    void shouldThrowStorageExceptionWhenPresignerFails() {
        S3CompatibleStorageService service = buildService();
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(SdkException.builder().message("Presigning failed").build());

        assertThatThrownBy(() -> service.generatePresignedUrl(FILE_PATH, PRESIGN_EXPIRY))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Error generating presigned URL for S3 object")
                .hasMessageContaining(FILE_PATH);
    }

    @Test
    void shouldCloseS3ClientAndPresignerInOrderWhenServiceIsClosed() {
        S3CompatibleStorageService service = buildService();

        service.close();

        InOrder inOrder = inOrder(s3Client, presigner);
        inOrder.verify(s3Client).close();
        inOrder.verify(presigner).close();
    }

    private S3CompatibleStorageService buildService() {
        return new S3CompatibleStorageService(s3Client, presigner, BUCKET_NAME, monitor);
    }

    private S3CompatibleStorageService buildServiceWithUploadSupport() {
        stubUtilitiesGetUrl();
        return new S3CompatibleStorageService(s3Client, presigner, BUCKET_NAME, monitor);
    }

    private void stubUtilitiesGetUrl() {
        try {
            S3Utilities utilities = mock(S3Utilities.class);
            when(s3Client.utilities()).thenReturn(utilities);
            when(utilities.getUrl(any(GetUrlRequest.class))).thenReturn(URI.create(FAKE_URL).toURL());
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid test URL", e);
        }
    }

    private void stubPresigner(String url) throws MalformedURLException {
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create(url).toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);
    }
}

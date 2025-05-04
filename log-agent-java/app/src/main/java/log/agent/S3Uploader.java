package log.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class S3Uploader {
    private static final Logger logger = LoggerFactory.getLogger(S3Uploader.class);
    private final S3Client s3Client;
    private final String bucketName;
    private final String environment;
    private final String appName;
    private final String vmIP;

    // Regex to find date DD-MM-YYYY and sequence number N in filenames like service-DD-MM-YYYY-N.log
    // Captures DD, MM, YYYY, N
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile(".*?-(\\d{2})-(\\d{2})-(\\d{4})-(\\d+)\\.log$");
    private static final DateTimeFormatter S3_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILENAME_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public S3Uploader(Properties props, String vmIP) throws IllegalArgumentException {
        this.bucketName = props.getProperty("s3.bucket");
        this.environment = props.getProperty("environment");
        this.appName = props.getProperty("app.name");
        this.vmIP = vmIP;

        String endpoint = props.getProperty("s3.endpoint");
        String accessKey = props.getProperty("s3.accessKey");
        String secretKey = props.getProperty("s3.secretKey");
        boolean disableSSL = Boolean.parseBoolean(props.getProperty("s3.disableSSL", "false"));
        boolean pathStyleAccess = Boolean.parseBoolean(props.getProperty("s3.pathStyleAccess", "true"));
        // Region might be needed even if not strictly used by Scality endpoint resolution
        String regionString = props.getProperty("s3.region", "us-east-1"); 
        Region region = Region.of(regionString);

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        SdkHttpClient httpClient = buildHttpClient(disableSSL);

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .build();

        try {
            this.s3Client = S3Client.builder()
                    .region(region) // Provide a region
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(credentialsProvider)
                    .serviceConfiguration(s3Configuration)
                    .httpClient(httpClient)
                    .build();
            logger.info("S3 Client initialized for endpoint: {}, bucket: {}", endpoint, bucketName);
        } catch (SdkClientException e) {
            logger.error("Failed to initialize S3 client: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to initialize S3 client", e);
        }
    }

    private SdkHttpClient buildHttpClient(boolean disableSSL) {
        UrlConnectionHttpClient.Builder builder = UrlConnectionHttpClient.builder();
        if (disableSSL) {
            logger.warn("Disabling SSL certificate validation for S3 connection. This is insecure and should only be used for testing or trusted environments.");
            try {
                // Create a trust manager that does not validate certificate chains
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());

                // Use the custom SSL context
                builder.tlsTrustManagersProvider(new TlsTrustManagersProvider() {
                    @Override
                    public TrustManager[] trustManagers() {
                        return trustAllCerts;
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to disable SSL validation: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to configure insecure SSL context", e);
            }
        }
        return builder.build();
    }

    public void uploadFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        Matcher matcher = LOG_FILE_PATTERN.matcher(fileName);

        if (!matcher.matches()) {
            logger.debug("Skipping file {}: does not match expected pattern *-DD-MM-YYYY-N.log", fileName);
            return;
        }

        try {
            String dd = matcher.group(1);
            String mm = matcher.group(2);
            String yyyy = matcher.group(3);
            // String sequence = matcher.group(4); // Sequence number available if needed

            String dateStringInput = String.format("%s-%s-%s", dd, mm, yyyy);
            LocalDate logDate = LocalDate.parse(dateStringInput, FILENAME_DATE_FORMAT);
            String dateStringS3 = logDate.format(S3_DATE_FORMAT); // Format as YYYY-MM-DD

            String s3Key = String.format("%s/%s/%s/%s/%s",
                    this.environment,
                    this.appName,
                    this.vmIP,
                    dateStringS3,
                    fileName
            );

            logger.info("Attempting to upload {} to s3://{}/{}", fileName, bucketName, s3Key);

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromFile(filePath));

            logger.info("Successfully uploaded {} to s3://{}/{}", fileName, bucketName, s3Key);

        } catch (DateTimeParseException e) {
            logger.warn("Skipping file {}: failed to parse date from filename: {}", fileName, e.getMessage());
        } catch (S3Exception e) {
            logger.error("Failed to upload {} to S3 (Bucket: {} Key: {}): {} (AWS Error Code: {})",
                    fileName, bucketName, "calculated_key", e.awsErrorDetails().errorMessage(), e.awsErrorDetails().errorCode(), e);
        } catch (SdkClientException e) {
            logger.error("Failed to upload {} to S3 due to client-side error: {}", fileName, e.getMessage(), e);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during upload of {}: {}", fileName, e.getMessage(), e);
        }
    }
}


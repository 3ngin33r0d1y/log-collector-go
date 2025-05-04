package log.dashboard.config;

import log.dashboard.config.VaultS3Properties.BucketCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Configuration
public class S3ClientConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(S3ClientConfiguration.class);

    private final VaultS3Properties vaultS3Properties;
    private final Map<String, S3Client> s3ClientCache = new ConcurrentHashMap<>();
    private SdkHttpClient sharedHttpClient;

    @Autowired
    public S3ClientConfiguration(VaultS3Properties vaultS3Properties) {
        this.vaultS3Properties = vaultS3Properties;
        initializeClients();
    }

    private void initializeClients() {
        logger.info("Initializing S3 clients based on Vault configuration...");
        if (vaultS3Properties == null || vaultS3Properties.getBuckets() == null || vaultS3Properties.getBuckets().isEmpty()) {
            logger.error("Vault S3 configuration is missing or empty. Cannot initialize S3 clients.");
            // Depending on requirements, you might throw an exception here or let the app fail later.
            return;
        }

        this.sharedHttpClient = buildHttpClient(vaultS3Properties.isDisableSSL());
        logger.info("Shared SdkHttpClient created (SSL Disabled: {}).", vaultS3Properties.isDisableSSL());

        for (BucketCredentials bucketCreds : vaultS3Properties.getBuckets()) {
            try {
                S3Client client = createS3Client(bucketCreds, vaultS3Properties.getEndpoint(), vaultS3Properties.isPathStyleAccess());
                s3ClientCache.put(bucketCreds.getName(), client);
                logger.info("Successfully created and cached S3 client for bucket: {}", bucketCreds.getName());
            } catch (Exception e) {
                logger.error("Failed to create S3 client for bucket {}: {}", bucketCreds.getName(), e.getMessage(), e);
                // Decide if failure for one bucket should prevent app startup
            }
        }
        logger.info("S3 client initialization complete. {} clients cached.", s3ClientCache.size());
    }

    private S3Client createS3Client(BucketCredentials creds, String endpoint, boolean pathStyleAccess) {
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(creds.getAccessKey(), creds.getSecretKey()));

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .build();

        // Use a default region, Scality might not use it but SDK requires one.
        Region region = Region.US_EAST_1;

        return S3Client.builder()
                .region(region)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(s3Configuration)
                .httpClient(this.sharedHttpClient) // Reuse the shared HTTP client
                .build();
    }

    private SdkHttpClient buildHttpClient(boolean disableSSL) {
        UrlConnectionHttpClient.Builder builder = UrlConnectionHttpClient.builder();
        if (disableSSL) {
            logger.warn("Disabling SSL certificate validation for S3 connection. This is insecure.");
            try {
                TrustManager[] trustAllCerts = { new InsecureTrustManager() };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());
                builder.tlsTrustManagersProvider(() -> trustAllCerts);
            } catch (Exception e) {
                throw new RuntimeException("Failed to configure insecure SSL context", e);
            }
        }
        return builder.build();
    }

    // Bean to provide the map of clients to other services
    @Bean
    public Map<String, S3Client> s3Clients() {
        return s3ClientCache;
    }

    // Simple insecure trust manager (use only if disableSSL is true)
    private static class InsecureTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}


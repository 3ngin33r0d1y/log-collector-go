package log.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "s3config") // Matches the root key expected AFTER Vault reads it
@Validated // Enable validation
public class VaultS3Properties {

    @NotBlank
    private String endpoint;
    private boolean disableSSL = false;
    private boolean pathStyleAccess = true;

    @NotEmpty
    private List<BucketCredentials> buckets;

    // Getters and Setters

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isDisableSSL() {
        return disableSSL;
    }

    public void setDisableSSL(boolean disableSSL) {
        this.disableSSL = disableSSL;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public List<BucketCredentials> getBuckets() {
        return buckets;
    }

    public void setBuckets(List<BucketCredentials> buckets) {
        this.buckets = buckets;
    }

    // Inner class for bucket credentials
    @Validated
    public static class BucketCredentials {
        @NotBlank
        private String name;
        @NotBlank
        private String accessKey;
        @NotBlank
        private String secretKey;

        // Getters and Setters

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BucketCredentials that = (BucketCredentials) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    // Need to ensure Vault JSON structure maps correctly.
    // Vault KV v2 stores data under a 'data' key.
    // Spring Cloud Vault needs to be configured to read {"endpoint": "...", "buckets": [...]} from the 'data' field.
    // The @ConfigurationProperties prefix "s3config" assumes Vault provides properties like s3config.endpoint, s3config.buckets[0].name etc.
    // This might require adjusting Vault JSON or Spring config if direct mapping fails.
}


package log.dashboard.service;

import log.dashboard.config.S3ClientConfiguration;
import log.dashboard.config.VaultS3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class LogService {

    private static final Logger logger = LoggerFactory.getLogger(LogService.class);
    private final Map<String, S3Client> s3Clients;
    private final VaultS3Properties vaultS3Properties;

    // Regex to extract sequence number N from filenames like service-DD-MM-YYYY-N.log
    private static final Pattern SEQUENCE_PATTERN = Pattern.compile(".*?-(\\d+)\\.log$");

    @Autowired
    public LogService(S3ClientConfiguration s3ClientConfiguration, VaultS3Properties vaultS3Properties) {
        this.s3Clients = s3ClientConfiguration.s3Clients();
        this.vaultS3Properties = vaultS3Properties;
    }

    public List<String> getAvailableBuckets() {
        return vaultS3Properties.getBuckets().stream()
                .map(VaultS3Properties.BucketCredentials::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    public List<LogFileEntry> listLogFiles(String bucket, String env, String appName, String date) {
        S3Client s3Client = getClientForBucket(bucket);
        String prefix = String.format("%s/%s/%s/%s/", env, appName, "*", date); // Use * for VM IP initially
        logger.info("Listing objects in bucket 	{}	 with prefix: {}", bucket, prefix);

        List<LogFileEntry> logFiles = new ArrayList<>();
        try {
            // Need to handle potential multiple VM IPs under the appName
            // First list common prefixes (VM IPs) under env/appName/
            ListObjectsV2Request listVmsRequest = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(String.format("%s/%s/", env, appName))
                    .delimiter("/")
                    .build();

            ListObjectsV2Response listVmsResponse = s3Client.listObjectsV2(listVmsRequest);
            logger.debug("Found VM IP prefixes: {}", listVmsResponse.commonPrefixes());

            for (CommonPrefix vmPrefix : listVmsResponse.commonPrefixes()) {
                String fullPrefix = vmPrefix.prefix() + date + "/"; // Construct full prefix including date
                logger.debug("Listing logs with full prefix: {}", fullPrefix);

                ListObjectsV2Request listLogsRequest = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(fullPrefix)
                        .build();

                // Paginate through results if necessary
                ListObjectsV2Response listResponse;
                do {
                    listResponse = s3Client.listObjectsV2(listLogsRequest);
                    for (S3Object s3Object : listResponse.contents()) {
                        String key = s3Object.key();
                        String fileName = key.substring(key.lastIndexOf("/") + 1);
                        int sequence = extractSequenceNumber(fileName);
                        logFiles.add(new LogFileEntry(key, fileName, s3Object.lastModified(), s3Object.size(), sequence));
                    }
                    listLogsRequest = listLogsRequest.toBuilder().continuationToken(listResponse.nextContinuationToken()).build();
                } while (listResponse.isTruncated());
            }

            // Sort by sequence number
            logFiles.sort(Comparator.comparingInt(LogFileEntry::getSequence));
            logger.info("Found {} log files for bucket={}, prefix={}", logFiles.size(), bucket, prefix);

        } catch (S3Exception e) {
            logger.error("S3 Error listing logs for bucket {}, prefix {}: {} (AWS Code: {})",
                    bucket, prefix, e.awsErrorDetails().errorMessage(), e.awsErrorDetails().errorCode(), e);
            // Consider throwing a custom exception
        } catch (SdkClientException e) {
            logger.error("SDK Client Error listing logs for bucket {}, prefix {}: {}", bucket, prefix, e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error listing logs for bucket {}, prefix {}: {}", bucket, prefix, e.getMessage(), e);
        }
        return logFiles;
    }

    public String getLogContent(String bucket, String key) {
        S3Client s3Client = getClientForBucket(bucket);
        logger.info("Fetching content for bucket={}, key={}", bucket, key);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3ObjectStream = s3Client.getObject(getObjectRequest);
             InputStreamReader streamReader = new InputStreamReader(s3ObjectStream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(streamReader)) {

            // Read line by line for potentially large files
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            logger.info("Successfully fetched content for key={}", key);
            return content.toString();

        } catch (S3Exception e) {
            logger.error("S3 Error getting object for bucket {}, key {}: {} (AWS Code: {})",
                    bucket, key, e.awsErrorDetails().errorMessage(), e.awsErrorDetails().errorCode(), e);
            return "Error fetching log content from S3: " + e.getMessage();
        } catch (IOException | SdkClientException e) {
            logger.error("Error reading S3 object stream for bucket {}, key {}: {}", bucket, key, e.getMessage(), e);
            return "Error reading log content: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error getting log content for bucket {}, key {}: {}", bucket, key, e.getMessage(), e);
            return "Unexpected error fetching log content: " + e.getMessage();
        }
    }

    public List<String> searchLogs(String bucket, String env, String appName, String date, String query) {
        S3Client s3Client = getClientForBucket(bucket);
        List<LogFileEntry> logFiles = listLogFiles(bucket, env, appName, date); // Reuse listing logic
        List<String> matchingFiles = new ArrayList<>();

        logger.info("Searching for 	{}	 across {} files in bucket={}, env={}, app={}, date={}",
                query, logFiles.size(), bucket, env, appName, date);

        for (LogFileEntry entry : logFiles) {
            String key = entry.getKey();
            logger.debug("Searching within file: {}", key);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            try (ResponseInputStream<GetObjectResponse> s3ObjectStream = s3Client.getObject(getObjectRequest);
                 InputStreamReader streamReader = new InputStreamReader(s3ObjectStream, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(streamReader)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(query)) {
                        logger.info("Found query 	{}	 in file: {}", query, entry.getFileName());
                        matchingFiles.add(entry.getFileName());
                        break; // Found a match, move to the next file
                    }
                }
            } catch (S3Exception e) {
                logger.error("S3 Error searching object for bucket {}, key {}: {} (AWS Code: {})",
                        bucket, key, e.awsErrorDetails().errorMessage(), e.awsErrorDetails().errorCode(), e);
            } catch (IOException | SdkClientException e) {
                logger.error("Error reading S3 object stream during search for bucket {}, key {}: {}", bucket, key, e.getMessage(), e);
            } catch (Exception e) {
                logger.error("Unexpected error searching log content for bucket {}, key {}: {}", bucket, key, e.getMessage(), e);
            }
        }
        logger.info("Search complete. Found {} matching files.", matchingFiles.size());
        return matchingFiles;
    }

    private S3Client getClientForBucket(String bucket) {
        S3Client client = s3Clients.get(bucket);
        if (client == null) {
            logger.error("No S3 client configured or found for bucket: {}", bucket);
            throw new IllegalArgumentException("Invalid or unconfigured bucket specified: " + bucket);
        }
        return client;
    }

    private int extractSequenceNumber(String fileName) {
        Matcher matcher = SEQUENCE_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                logger.warn("Could not parse sequence number from filename: {}", fileName);
            }
        }
        return Integer.MAX_VALUE; // Return large number if no sequence found, sorts last
    }
}


package log.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class AgentConfig {
    private static final Logger logger = LoggerFactory.getLogger(AgentConfig.class);

    public Properties loadConfig(String path) throws IOException, IllegalArgumentException {
        Properties props = new Properties();

        if (!Files.exists(Paths.get(path))) {
            throw new IOException("Configuration file not found: " + path);
        }

        try (InputStream input = new FileInputStream(path)) {
            props.load(input);
            logger.info("Loaded configuration from {}", path);
        } catch (IOException ex) {
            logger.error("Error reading configuration file {}: {}", path, ex.getMessage());
            throw ex;
        }

        validateProperties(props);
        return props;
    }

    private void validateProperties(Properties props) throws IllegalArgumentException {
        String[] requiredKeys = {
                "log.directory", "app.name", "environment",
                "s3.endpoint", "s3.bucket", "s3.accessKey", "s3.secretKey"
        };

        for (String key : requiredKeys) {
            if (props.getProperty(key) == null || props.getProperty(key).trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required configuration property: " + key);
            }
        }

        // Optional boolean validation with default
        props.putIfAbsent("s3.disableSSL", "false");
        props.putIfAbsent("s3.pathStyleAccess", "true"); // Default to true for Scality

        try {
            Boolean.parseBoolean(props.getProperty("s3.disableSSL"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid boolean value for s3.disableSSL: " + props.getProperty("s3.disableSSL"));
        }
        try {
            Boolean.parseBoolean(props.getProperty("s3.pathStyleAccess"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid boolean value for s3.pathStyleAccess: " + props.getProperty("s3.pathStyleAccess"));
        }

        logger.debug("Configuration properties validated successfully.");
    }
}


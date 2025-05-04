package log.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Properties;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Starting Java Log Agent...");

        String configPath = "agent-config.properties";
        if (args.length > 0) {
            configPath = args[0];
            logger.info("Using configuration file path from command line: {}", configPath);
        } else {
            logger.info("Using default configuration file path: {}", configPath);
        }

        AgentConfig config = new AgentConfig();
        try {
            Properties props = config.loadConfig(configPath);
            logger.info("Configuration loaded successfully.");

            String vmIP = getVmIpAddress();
            logger.info("Detected VM IP Address: {}", vmIP);

            S3Uploader uploader = new S3Uploader(props, vmIP);
            logger.info("S3 Uploader initialized.");

            Path dirToWatch = Paths.get(props.getProperty("log.directory"));
            DirectoryWatcher watcher = new DirectoryWatcher(dirToWatch, uploader);

            // Perform initial scan
            logger.info("Performing initial scan of directory: {}", dirToWatch);
            watcher.scanDirectory();
            logger.info("Initial scan complete.");

            // Start watching
            logger.info("Starting to watch directory: {}", dirToWatch);
            watcher.watch(); // This will block

        } catch (IOException e) {
            logger.error("Failed to load configuration from {}: {}", configPath, e.getMessage(), e);
            System.exit(1);
        } catch (IllegalArgumentException e) {
            logger.error("Configuration error: {}", e.getMessage(), e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during agent startup: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static String getVmIpAddress() throws SocketException {
        // Attempt to find a non-loopback IPv4 address
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface ni = networkInterfaces.nextElement();
            if (!ni.isLoopback() && ni.isUp()) {
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress ia = inetAddresses.nextElement();
                    if (!ia.isLoopbackAddress() && ia.isSiteLocalAddress() && ia.getAddress().length == 4) { // Check for IPv4
                        return ia.getHostAddress();
                    }
                }
            }
        }
        logger.warn("Could not automatically determine a non-loopback IPv4 address. Falling back to localhost name.");
        try {
            return InetAddress.getLocalHost().getHostAddress(); // Fallback
        } catch (Exception e) {
            logger.error("Failed to get localhost address as fallback: {}", e.getMessage());
            return "unknown-ip"; // Final fallback
        }
    }
}


package log.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class DirectoryWatcher {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryWatcher.class);
    private final Path dirToWatch;
    private final S3Uploader uploader;
    private final WatchService watchService;

    public DirectoryWatcher(Path dirToWatch, S3Uploader uploader) throws IOException {
        if (!Files.isDirectory(dirToWatch)) {
            throw new IOException("Provided path is not a directory: " + dirToWatch);
        }
        this.dirToWatch = dirToWatch;
        this.uploader = uploader;
        this.watchService = FileSystems.getDefault().newWatchService();
        // Register the directory to watch for entry creation and modification events.
        // ENTRY_DELETE could be added if needed, but upload is typically on create/modify.
        dirToWatch.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        logger.info("Registered directory {} for watching events.", dirToWatch);
    }

    /**
     * Scans the directory initially for any existing .log files and attempts to upload them.
     */
    public void scanDirectory() {
        try (Stream<Path> stream = Files.list(dirToWatch)) {
            stream.filter(Files::isRegularFile)
                  .filter(path -> path.toString().endsWith(".log"))
                  .forEach(path -> {
                      logger.info("Found existing log file during initial scan: {}", path.getFileName());
                      // Add a small delay or check modification time if needed to avoid uploading partial files
                      // For simplicity here, we just attempt upload.
                      uploader.uploadFile(path);
                  });
        } catch (IOException e) {
            logger.error("Error during initial scan of directory {}: {}", dirToWatch, e.getMessage(), e);
        }
    }

    /**
     * Starts the watching process. This method blocks indefinitely.
     */
    public void watch() {
        WatchKey key;
        try {
            while ((key = watchService.take()) != null) {
                // Small delay to potentially batch events or wait for file writes to settle
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException ignored) {}

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // Context for directory entry event is the file name of entry
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path filePath = dirToWatch.resolve(fileName);

                    // Process only .log files
                    if (!filePath.toString().endsWith(".log")) {
                        continue;
                    }

                    logger.debug("Detected event [{}] for file: {}", kind.name(), filePath);

                    // Handle create and modify events
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        // Check if the file is a regular file before attempting upload
                        if (Files.isRegularFile(filePath)) {
                            // Add a delay to try and ensure the file write is complete.
                            // A more robust solution might involve checking file locks or modification times.
                            try {
                                logger.debug("Waiting briefly before uploading {}...", filePath.getFileName());
                                TimeUnit.SECONDS.sleep(2); // Wait 2 seconds
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                logger.warn("Watch delay interrupted for file {}", filePath.getFileName());
                            }
                            logger.info("Processing event [{}] for log file: {}", kind.name(), filePath.getFileName());
                            uploader.uploadFile(filePath);
                        } else {
                            logger.debug("Ignoring event for non-regular file or directory: {}", filePath);
                        }
                    } else if (kind == StandardWatchEventKinds.OVERFLOW) {
                        logger.warn("WatchService OVERFLOW event detected for directory {}. Some events might have been lost.", dirToWatch);
                        // Consider re-scanning the directory if overflow occurs
                        // scanDirectory(); 
                    }
                }

                // Reset the key -- this step is critical to receive further watch events.
                // If the key is no longer valid, the directory is inaccessible so exit the loop.
                boolean valid = key.reset();
                if (!valid) {
                    logger.error("Watch key for directory {} is no longer valid. Stopping watcher.", dirToWatch);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Directory watcher thread interrupted for {}. Stopping watcher.", dirToWatch);
        } catch (ClosedWatchServiceException e) {
            logger.info("Watch service closed for directory {}. Stopping watcher.", dirToWatch);
        } catch (Exception e) {
            logger.error("An unexpected error occurred in the directory watcher for {}: {}", dirToWatch, e.getMessage(), e);
        }

        // Cleanup
        closeWatchService();
    }

    public void closeWatchService() {
        try {
            if (watchService != null) {
                watchService.close();
                logger.info("Closed watch service for directory {}.", dirToWatch);
            }
        } catch (IOException e) {
            logger.error("Error closing watch service for directory {}: {}", dirToWatch, e.getMessage(), e);
        }
    }
}


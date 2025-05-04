package log.dashboard.web;

import log.dashboard.service.LogFileEntry;
import log.dashboard.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LogController {

    private static final Logger logger = LoggerFactory.getLogger(LogController.class);
    private final LogService logService;

    @Autowired
    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/config/buckets")
    public ResponseEntity<List<String>> getBuckets() {
        try {
            List<String> buckets = logService.getAvailableBuckets();
            return ResponseEntity.ok(buckets);
        } catch (Exception e) {
            logger.error("Error retrieving available buckets: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving bucket list");
        }
    }

    @GetMapping("/logs")
    public ResponseEntity<List<LogFileEntry>> listLogs(
            @RequestParam String bucket,
            @RequestParam String env,
            @RequestParam String appName,
            @RequestParam String date) { // Expecting YYYY-MM-DD format
        try {
            // Basic validation
            if (bucket.isEmpty() || env.isEmpty() || appName.isEmpty() || date.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required parameters (bucket, env, appName, date)");
            }
            // Add more specific date format validation if needed

            List<LogFileEntry> logFiles = logService.listLogFiles(bucket, env, appName, date);
            return ResponseEntity.ok(logFiles);
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request for listing logs: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Error listing log files for bucket={}, env={}, app={}, date={}: {}",
                    bucket, env, appName, date, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error listing log files");
        }
    }

    @GetMapping(value = "/log-content", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getLogContent(
            @RequestParam String bucket,
            @RequestParam String key) {
        try {
            if (bucket.isEmpty() || key.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required parameters (bucket, key)");
            }
            String content = logService.getLogContent(bucket, key);
            // Check if service returned an error message
            if (content.startsWith("Error")) {
                 // Logged in service, return appropriate status
                 if (content.contains("Invalid or unconfigured bucket")) {
                     throw new ResponseStatusException(HttpStatus.BAD_REQUEST, content);
                 } else {
                     throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, content);
                 }
            }
            return ResponseEntity.ok(content);
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request for getting log content: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResponseStatusException rse) {
            throw rse; // Re-throw exceptions already mapped to status codes
        } catch (Exception e) {
            logger.error("Error getting log content for bucket={}, key={}: {}", bucket, key, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving log content");
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<String>> searchLogs(
            @RequestParam String bucket,
            @RequestParam String env,
            @RequestParam String appName,
            @RequestParam String date,
            @RequestParam String query) {
        try {
            if (bucket.isEmpty() || env.isEmpty() || appName.isEmpty() || date.isEmpty() || query.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required parameters (bucket, env, appName, date, query)");
            }
            List<String> matchingFiles = logService.searchLogs(bucket, env, appName, date, query);
            return ResponseEntity.ok(matchingFiles);
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request for searching logs: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Error searching logs for bucket={}, env={}, app={}, date={}, query={}: {}",
                    bucket, env, appName, date, query, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error searching logs");
        }
    }
}


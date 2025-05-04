# System Architecture: Java Log Monitoring and Dashboard (Vault Integrated)

## 1. Overview

This document outlines the architecture for a revised system using Java to monitor `.log` files on Red Hat VMs, upload them to a Scality S3-compatible storage service, and view them through a web dashboard. This version integrates HashiCorp Vault for managing S3 bucket configurations and credentials, supports multiple S3 buckets, and features an enhanced dashboard.

The system consists of three main components:
1.  **Java Agent (`log-agent`):** A standalone Java application (JAR) running on each Red Hat VM. It monitors specified log directories, parses log filenames, and uploads logs directly to a configured S3 bucket.
2.  **Combined Backend/Frontend Application (`log-dashboard`):** A single Spring Boot 3 application (packaged as an executable JAR) that includes:
    *   **Spring Boot Backend:** Provides REST APIs, integrates with Vault (via AppRole) to fetch S3 configurations for multiple buckets, interacts with Scality S3 using the AWS SDK for Java v2, and serves the frontend.
    *   **React Frontend:** A web interface for users to select buckets, filter logs (env, app, date), navigate sequentially through log files, search log content, and view logs.
3.  **HashiCorp Vault:** Securely stores S3 configurations (bucket list, credentials, common endpoint URL) accessed by the backend.
4.  **Scality S3:** S3-compatible storage serving as the central repository for logs.

## 2. Components

### 2.1. Java Agent (`log-agent`)

*   **Language:** Java (JDK 17+)
*   **Build Tool:** Gradle
*   **Packaging:** Executable JAR (`log-agent.jar`)
*   **Functionality:**
    *   Reads configuration from a local properties file (`agent-config.properties`).
    *   Monitors the directory specified in `log.directory` using Java NIO `WatchService`.
    *   Detects new/modified files matching `*.log` and specifically parses filenames like `service-DD-MM-YYYY-N.log` (extracts date `DD-MM-YYYY` and sequence `N`).
    *   Fetches the VM's IP address dynamically.
    *   Constructs the S3 object key: `ENV/AppName/VMIP/YYYY-MM-DD/original_filename.log` (using `environment` and `app.name` from config).
    *   Uses AWS SDK for Java v2, configured with endpoint, credentials, bucket name, and SSL settings from `agent-config.properties`, to upload the log file directly to Scality S3.
    *   Handles errors during monitoring and upload.
*   **Configuration (`agent-config.properties`):**
    ```properties
    # Monitoring Config
    log.directory=/var/log/my-app
    app.name=my-app-name
    environment=PROD
    
    # S3 Config (for direct upload by agent)
    s3.endpoint=http://s3.your-scality.com # Scality Endpoint URL
    s3.bucket=the-target-bucket-for-this-agent # Specific bucket this agent uploads to
    s3.accessKey=AGENT_SCALITY_ACCESS_KEY
    s3.secretKey=AGENT_SCALITY_SECRET_KEY
    s3.disableSSL=false # Set to true for HTTP or self-signed certs
    s3.pathStyleAccess=true # Often required for non-AWS S3
    ```
*   **Dependencies:** AWS SDK for Java v2 (S3), logging framework (Logback/SLF4j).
*   **Deployment:** Delivered as `log-agent.jar`. Deployed via Ansible, placing the JAR and `agent-config.properties` (with restricted permissions) on the VM. Managed via a systemd service.

### 2.2. Combined Backend/Frontend Application (`log-dashboard`)

*   **Framework:** Spring Boot 3
*   **Language:** Java (JDK 17+), TypeScript (React)
*   **Build Tool:** Gradle (configured to build Spring Boot app and include React build artifacts)
*   **Packaging:** Single executable JAR (`log-dashboard.jar`)
*   **Functionality:**
    *   **Spring Boot Backend:**
        *   Serves the static React frontend assets from within the JAR.
        *   Provides REST APIs (prefixed with `/api`).
        *   Integrates with Vault using Spring Cloud Vault or Vault Java Driver.
        *   Authenticates to Vault using AppRole (reads Vault address, Role ID, Secret ID from environment variables: `VAULT_ADDR`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`).
        *   Reads S3 configuration JSON from Vault path `Secret/XSf/s3-log-config/dev/default` (KV v2 assumed).
        *   Parses the JSON to get the common Scality endpoint, SSL settings, and the list of buckets with their specific credentials.
        *   Caches the fetched Vault secrets with appropriate TTL.
        *   Manages a map of S3 clients (AWS SDK for Java v2), one per bucket, configured using the common endpoint/SSL settings and bucket-specific credentials from Vault.
        *   API Endpoints:
            *   `GET /api/config/buckets`: Returns list of bucket names available from Vault config.
            *   `GET /api/logs`: Takes `bucket`, `env`, `appName`, `date` as query params. Uses the S3 client for the specified `bucket` to list objects matching the prefix. Returns a sorted list of log file details (key, size, modified, sequence number).
            *   `GET /api/log-content`: Takes `bucket`, `key` as query params. Uses the S3 client for the `bucket` to fetch and return the content of the specified log file.
            *   `GET /api/search`: Takes `bucket`, `env`, `appName`, `date`, `query` as params. Lists relevant log files, downloads/streams content, performs text search, and returns a list of filenames containing the query term.
    *   **React Frontend:**
        *   Built using `create-react-app` or similar.
        *   Build artifacts (`index.html`, JS, CSS) included in the `static` resources directory of the Spring Boot JAR.
        *   UI: Dropdown for Bucket selection, Env filter, App/Date selectors, sorted log file list, content view with Next/Previous buttons (based on sequence number), search input, search results (filenames).
        *   Calls backend `/api/*` endpoints.
*   **Dependencies:** Spring Boot Web, Spring Cloud Vault (optional, or Vault Java Driver), AWS SDK for Java v2, Jackson (JSON), Gradle frontend plugin (for React build).
*   **Deployment:** Single `log-dashboard.jar`. Run using `java -jar log-dashboard.jar`. Requires Java runtime and environment variables for Vault (`VAULT_ADDR`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`). Managed via systemd or container orchestrator.

### 2.3. HashiCorp Vault

*   **Purpose:** Centralized, secure storage for S3 configurations.
*   **Path:** `Secret/XSf/s3-log-config/dev/default` (KV v2 engine assumed).
*   **Authentication:** AppRole. The backend application needs `VAULT_ROLE_ID` and `VAULT_SECRET_ID` provided via environment variables.
*   **Policy:** Vault policy attached to the AppRole must grant `read` access to the secret path.
*   **JSON Structure (Example in Vault at the specified path):**
    ```json
    {
      "data": { 
        "endpoint": "http://s3.your-scality.com", 
        "disableSSL": false,
        "pathStyleAccess": true,
        "buckets": [
          {
            "name": "prod-logs-bucket",
            "accessKey": "PROD_ACCESS_KEY",
            "secretKey": "PROD_SECRET_KEY"
          },
          {
            "name": "dev-logs-bucket",
            "accessKey": "DEV_ACCESS_KEY",
            "secretKey": "DEV_SECRET_KEY"
          }
        ]
      }
    }
    ```

### 2.4. Scality S3

*   **Purpose:** Stores logs uploaded by agents.
*   **Access:** Accessed by agents (direct upload) and the backend (read/search) using credentials stored in agent config file and Vault respectively.
*   **Endpoint:** A single, common endpoint URL used for all buckets, specified in the Vault secret.

## 3. Data Flow

1.  **Log Creation & Upload:**
    *   Application on VM writes log `service-DD-MM-YYYY-N.log`.
    *   Java Agent detects the file.
    *   Agent reads `agent-config.properties` (log dir, app, env, S3 target bucket, endpoint, credentials).
    *   Agent constructs S3 key (`ENV/AppName/VMIP/YYYY-MM-DD/service-DD-MM-YYYY-N.log`).
    *   Agent uploads log to its configured Scality S3 bucket.
2.  **Dashboard Interaction:**
    *   User accesses the Spring Boot application URL.
    *   React frontend loads.
    *   Frontend calls `GET /api/config/buckets`.
    *   Backend authenticates to Vault (AppRole), reads secret, caches config, returns bucket names.
    *   User selects Bucket, Env, App, Date.
    *   Frontend calls `GET /api/logs`.
    *   Backend gets cached S3 config (endpoint, credentials) for the selected bucket.
    *   Backend lists objects in S3, sorts by sequence number, returns list to frontend.
    *   User selects a log file.
    *   Frontend calls `GET /api/log-content`.
    *   Backend gets credentials, fetches content from S3, returns to frontend.
    *   User clicks Next/Previous -> Frontend calculates next/previous key, calls `GET /api/log-content`.
    *   User searches -> Frontend calls `GET /api/search`.
    *   Backend gets credentials, lists files, downloads/searches content, returns matching filenames.

## 4. Security Considerations

*   **Vault:** Secure AppRole Role ID/Secret ID for the backend (env vars). Restrictive Vault policy.
*   **Agent:** Secure S3 credentials in `agent-config.properties` via file permissions.
*   **Network:** Firewalls/Security Groups for VMs, Backend, Vault, S3 endpoint.
*   **Backend:** Input validation for API requests.

## 5. Build and Deployment

*   **Build:** Use Gradle to build the agent JAR and the combined Spring Boot/React JAR.
*   **Agent Deployment:** Ansible deploys agent JAR and config file; systemd manages the service.
*   **Backend/Frontend Deployment:** Deploy the single JAR using systemd or container orchestration. Provide Vault environment variables.



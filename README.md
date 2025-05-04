# Java Log Monitoring and Dashboard (Vault Integrated)

## Overview

This project provides a system for monitoring log files on Red Hat VMs, uploading them to Scality S3-compatible storage, and viewing them through a web dashboard. The system consists of two main components:

1.  **Java Log Agent (`log-agent-java`):** A standalone Java application (packaged as a JAR) designed to run on target VMs. It monitors a specified directory for `.log` files, parses date information from filenames (e.g., `service-DD-MM-YYYY-N.log`), and uploads logs to a Scality S3 bucket under a structured path (`ENV/AppName/VMIP/YYYY-MM-DD/`).
2.  **Java Dashboard Application (`log-dashboard-java`):** A Spring Boot 3 application combined with a React frontend. It serves the web UI and provides backend APIs to interact with HashiCorp Vault to retrieve S3 bucket configurations and credentials, and then lists and fetches logs from the configured Scality S3 buckets.

## Features

*   **Agent:**
    *   Monitors a configurable directory for `.log` files.
    *   Extracts date (`DD-MM-YYYY`) and sequence number from log filenames.
    *   Uploads logs to Scality S3 with a path structure: `ENV/AppName/VMIP/YYYY-MM-DD/service-DD-MM-YYYY-N.log`.
    *   Configurable via a properties file.
    *   Built with Java 17 and Gradle.
*   **Dashboard:**
    *   Spring Boot 3 backend with embedded React frontend.
    *   Integrates with HashiCorp Vault using AppRole authentication to securely fetch S3 bucket lists and credentials.
    *   Supports multiple S3 buckets configured in Vault.
    *   Dropdown selection for Bucket, Environment (ENV), Application Name (AppName), and Date.
    *   Displays a list of log files for the selected criteria.
    *   Allows viewing log content with simple Next/Previous navigation for files within the same date folder.
    *   Basic search functionality across log content within the selected scope (returns matching filenames).
    *   Built with Java 17, Spring Boot 3, React (TypeScript), and Gradle.

## Prerequisites

*   **Java Development Kit (JDK):** Version 17 or later.
*   **Gradle:** Version 8.8 or later (the wrapper is included, but ensure Gradle can run).
*   **Node.js and npm:** Required for building the React frontend (usually handled by the Gradle build process using the `node-gradle` plugin).
*   **HashiCorp Vault:** A running instance accessible by the dashboard application. AppRole authentication must be enabled.
*   **Scality S3 Storage:** Access to a Scality S3-compatible object storage endpoint.
*   **Network Access:** VMs running the agent need network access to the Scality S3 endpoint. The server running the dashboard needs access to both Vault and the Scality S3 endpoint.

## Project Structure

```
/
├── log-agent-java/             # Java Log Agent source code and build files
│   ├── app/
│   │   ├── build.gradle
│   │   └── src/
│   │       ├── main/java/log/agent/...
│   │       └── test/java/log/agent/...
│   ├── gradle/
│   ├── gradlew
│   └── gradlew.bat
├── log-dashboard-java/         # Java Dashboard (Spring Boot + React) source code
│   ├── app/
│   │   ├── build.gradle
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/log/dashboard/...
│   │       │   ├── frontend/         # React frontend source code
│   │       │   │   ├── public/
│   │       │   │   ├── src/
│   │       │   │   ├── package.json
│   │       │   │   └── ...
│   │       │   └── resources/
│   │       │       └── application.properties
│   │       └── test/java/log/dashboard/...
│   ├── gradle/
│   ├── gradlew
│   └── gradlew.bat
├── java_architecture_design.md # System architecture document
├── README.md                   # This file
└── todo.md                     # Task checklist
```

## Java Log Agent (`log-agent-java`)

### Configuration

The agent is configured via a properties file (e.g., `config.properties`). Pass the path to this file using the `-config` command-line argument.

**`config.properties` Example:**

```properties
# Directory to monitor for .log files
monitor.directory=/var/log/my-app

# Application Name (used in S3 path)
app.name=cash2atm

# Environment (used in S3 path, e.g., DEV, PRD)
environment=PRD

# VM IP Address (used in S3 path). If left empty, the agent attempts to auto-detect.
vm.ip=10.0.1.123

# Scality S3 Configuration
s3.bucket=my-log-bucket-name
s3.endpoint=http://s3.your-scality-domain.com
s3.accessKey=YOUR_SCALITY_ACCESS_KEY
s3.secretKey=YOUR_SCALITY_SECRET_KEY

# Optional: Disable SSL verification if needed for Scality endpoint (default: false)
s3.disableSslVerification=false
```

**Note:** Storing S3 keys directly in the agent's config file is simple but less secure. For production, consider using mechanisms like IAM roles (if applicable to your VM environment) or fetching credentials securely, potentially from Vault if the agent also needs Vault access (though the current design focuses Vault interaction on the backend).

### Building

Navigate to the agent's root directory and use Gradle to build the executable JAR.

```bash
cd /path/to/log-agent-java
./gradlew build 
# Or for a fat JAR including dependencies (if shadow plugin is configured):
# ./gradlew shadowJar 
```

The build output (e.g., `log-agent-java/app/build/libs/app-all.jar` or similar) will be created.

### Running

Run the agent JAR file, providing the path to your configuration file.

```bash
java -jar /path/to/log-agent-java/app/build/libs/app-all.jar -config /path/to/your/config.properties
```

(Adjust the JAR filename based on the actual output from the build process).

## Dashboard Application (`log-dashboard-java`)

### Configuration

The dashboard application uses Spring Boot's standard configuration mechanisms (`application.properties`) and environment variables, especially for sensitive Vault details.

**`application.properties`:**

Located at `log-dashboard-java/app/src/main/resources/application.properties`.

```properties
# Server port
server.port=8080

# Vault Configuration (URI and AppRole credentials typically provided by Env Vars)
spring.application.name=log-dashboard
spring.cloud.vault.authentication=APPROLE
spring.cloud.vault.kv.enabled=true
spring.cloud.vault.kv.backend=secret # Adjust if your KV backend path is different
# The path where the S3 JSON config is stored
spring.cloud.vault.kv.application-name=XSf/s3-log-config/dev/default 

# Logging
logging.level.log.dashboard=DEBUG
logging.level.org.springframework.vault=INFO
logging.level.software.amazon.awssdk=INFO

# Scality S3 Specifics (Endpoint is read from Vault JSON, but provide defaults/fallbacks if needed)
# scality.s3.endpoint=http://fallback-s3.your-scality-domain.com 
scality.s3.disableSslVerification=false # Set to true if your Scality endpoint uses self-signed certs
```

**Environment Variables:**

The following environment variables **must** be set when running the dashboard application:

*   `VAULT_ADDR`: The URI of your HashiCorp Vault instance (e.g., `http://127.0.0.1:8200`).
*   `VAULT_ROLE_ID`: The Role ID for the AppRole the dashboard will use to authenticate.
*   `VAULT_SECRET_ID`: The Secret ID for the AppRole.

### Vault Setup

1.  **Ensure Vault is Running:** Your Vault instance must be unsealed and accessible from where the dashboard application will run.
2.  **Enable AppRole:** If not already enabled:
    ```bash
    vault auth enable approle
    ```
3.  **Create Policy:** Define a policy that grants read access to the secret path. Create a file `log-dashboard-policy.hcl`:
    ```hcl
    path "secret/data/XSf/s3-log-config/dev/default" {
      capabilities = ["read"]
    }
    ```
    Apply the policy:
    ```bash
    vault policy write log-dashboard-policy log-dashboard-policy.hcl
    ```
4.  **Create AppRole:** Create a role named (for example) `log-dashboard-role` linked to the policy:
    ```bash
    vault write auth/approle/role/log-dashboard-role token_policies="log-dashboard-policy" token_ttl=1h token_max_ttl=4h
    ```
5.  **Get Role ID and Secret ID:**
    ```bash
    vault read auth/approle/role/log-dashboard-role/role-id
    # Copy the role_id

    vault write -f auth/approle/role/log-dashboard-role/secret-id
    # Copy the secret_id
    ```
    Securely provide these IDs to the dashboard application via the `VAULT_ROLE_ID` and `VAULT_SECRET_ID` environment variables.
6.  **Store S3 Configuration:** Write the S3 bucket configuration JSON to the specified path in Vault:
    ```bash
    vault kv put secret/XSf/s3-log-config/dev/default buckets:='[{"name": "bucket1", "endpoint": "http://s3.your-scality.com", "accessKey": "KEY1", "secretKey": "SECRET1"}, {"name": "bucket2", "endpoint": "http://s3.your-scality.com", "accessKey": "KEY2", "secretKey": "SECRET2"}]'
    ```
    *(Adjust the JSON content and path according to your actual buckets, keys, and endpoint. Ensure the endpoint is consistent if `scality.s3.endpoint` property isn't used as a fallback)*.

### Building

Navigate to the dashboard's root directory and use Gradle. The build process includes compiling Java code, running Node/npm to build the React frontend, and packaging everything into a single executable Spring Boot JAR.

```bash
cd /path/to/log-dashboard-java
./gradlew build
```

The build output (e.g., `log-dashboard-java/app/build/libs/app-0.0.1-SNAPSHOT.jar`) will be created.

### Running

Ensure the required environment variables (`VAULT_ADDR`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`) are set, then run the JAR file:

```bash
export VAULT_ADDR="http://your-vault-address:8200"
export VAULT_ROLE_ID="your-role-id"
export VAULT_SECRET_ID="your-secret-id"

java -jar /path/to/log-dashboard-java/app/build/libs/app-0.0.1-SNAPSHOT.jar
```

Access the dashboard in your browser, typically at `http://localhost:8080` (or the configured server port).

## Deployment Notes

*   **Agent:** Use configuration management tools like Ansible to deploy the agent JAR and its `config.properties` file to target VMs. Ensure the Java runtime (JRE 17+) is available on the VMs.
*   **Dashboard:** Deploy the dashboard JAR to a server with Java 17+ runtime. Ensure network connectivity to Vault and Scality S3. Consider containerizing the application (Dockerfile not included) for easier deployment and scaling. Manage Vault credentials securely (e.g., inject environment variables via deployment system, use Kubernetes Vault integration if applicable).
*   **Security:** Review and restrict S3 bucket policies and Vault policies to the minimum required permissions. Protect Vault Role ID and Secret ID.

## Validation Limitations

Full end-to-end validation, including live interaction with HashiCorp Vault and Scality S3, could not be performed within the development sandbox environment. The build process confirms code compilation and packaging, but runtime testing requires deployment in an environment with access to these external services.


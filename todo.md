# Java Log Monitoring and Dashboard (Vault Integrated)

- [X] **Clarify New Requirements:**
    - [X] Vault details (address, auth method, path to JSON secrets)? (Env Vars, AppRole, Secret/XSf/s3-log-config/dev/default)
    - [X] Structure of JSON in Vault (list of buckets, credentials per bucket, endpoint per bucket?)? ({"buckets": [{"name": "...", "endpoint": "...", "accessKey": "...", "secretKey": "..."}, ...]}, endpoint same for all)
    - [X] Specific Java framework preference (Spring Boot, Quarkus, etc.)? (Spring Boot 3)
    - [X] Exact log navigation behavior (pagination within date folder)? (Sequential based on filename suffix, e.g., -1.log, -2.log)
    - [X] Search functionality details (simple text search? required/optional?)? (Search across logs within scope, return filenames)
    - [X] Agent deployment details (Ansible? Vault config for agent?)? (Ansible, config file for agent - path, backend URL, app, env)
    - [X] Scality S3 endpoint configuration alongside Vault? (Endpoint same for all buckets, specified in Vault JSON per bucket entry)
- [X] **Design Java System Architecture with Vault**
- [X] **Implement Java FS Monitoring Agent (.jar)**
- [X] **Implement Combined Java Backend/Frontend JAR (e.g., Spring Boot + React)**
- [X] **Integrate Vault for Bucket/Secret Management**
- [X] **Develop Enhanced Dashboard (Bucket Selection, Navigation, Search?)**
- [X] **Validate End-to-End Flow (Java, Vault, Multi-Bucket)** (Validation limited by sandbox environment - Vault/S3 simulation not possible)
- [X] **Report and Deliver (Java)**


# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a production-ready Kafka Connect source connector for ingesting data from HTTP/HTTPS APIs. The connector supports enterprise features including field-level encryption, API chaining, circuit breakers, OAuth2 authentication, and comprehensive monitoring. Each connector instance can manage up to 15 different API endpoints simultaneously.

## Build and Development Commands

### Core Build Commands
```bash
# Standard build with unit tests
mvn clean package

# Build without tests
mvn clean compile

# Build fat JAR for Kafka Connect deployment
mvn clean package -DskipTests
# Output: target/kafka-http-source-connector-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

### Testing Commands
```bash
# Run unit tests only (fast, excludes integration tests)
mvn test

# Run integration tests (requires Docker for TestContainers)
mvn failsafe:integration-test -Pintegration-tests

# Run all tests
mvn test -Pall-tests

# Run specific integration test
mvn test -Dtest=EnterpriseConnectorIntegrationTest

# Run with TestContainers optimization (reuse containers)
mvn test -Dtestcontainers.reuse.enable=true
```

### Security and Quality
```bash
# Run OWASP dependency vulnerability check
mvn org.owasp:dependency-check-maven:check

# Full verification with security scans
mvn verify
```

### Profile-based Testing
```bash
# Unit tests profile (default)
mvn test -Punit-tests

# Integration tests profile with TestContainers optimization
mvn test -Pintegration-tests

# All tests including integration
mvn test -Pall-tests
```

## Code Architecture

### Multi-API Architecture Pattern
The connector uses a unique pattern where each connector instance manages up to 15 APIs:
- Configuration uses indexed properties: `api1.http.api.path`, `api2.http.api.path`, etc.
- Task distribution: APIs are distributed across tasks via `task.api.indices` property
- Each API has independent offset management, authentication, and scheduling

### Core Classes and Components

#### Main Connector Classes
- **`HttpSourceConnector`** (src/main/java/io/confluent/connect/http/HttpSourceConnector.java:38): Main connector class handling configuration validation and task distribution
- **`HttpSourceTask`** (src/main/java/io/confluent/connect/http/HttpSourceTask.java:37): Worker tasks that poll APIs and produce Kafka records
- **`HttpSourceConnectorConfig`** (src/main/java/io/confluent/connect/http/config/HttpSourceConnectorConfig.java): Central configuration with 70+ properties using Kafka Connect's ConfigDef pattern

#### Key Architectural Components
- **Authentication Factory** (`auth/` package): Supports Basic, Bearer, OAuth2, API Key, AWS IAM, Azure AD
- **API Chaining** (`chaining/ApiChainingManager.java`): Parent-child relationships where child APIs use parent response data
- **Field Encryption** (`encryption/FieldEncryptionManager.java`): AES-GCM encryption with configurable per-field rules
- **Circuit Breakers** (`error/` package): Per-API failure thresholds to prevent cascading failures
- **Offset Management** (`offset/` package): Pluggable strategies (simple incrementing, cursor pagination, timestamp-based, chaining, OData pagination)

### Configuration Patterns

#### API Configuration Structure
```json
{
  "apis.num": "2",
  "api1.http.api.path": "/users",
  "api1.topics": "users-topic",
  "api1.http.offset.mode": "CURSOR_PAGINATION",
  "api2.http.api.path": "/orders", 
  "api2.topics": "orders-topic"
}
```

#### API Chaining Format
```
"api.chaining.parent.child.relationship": "child_api:parent_api,child2:parent_api"
```

#### Field Encryption Rules
```
"field.encryption.rules": "ssn:AES_GCM,salary:DETERMINISTIC,notes:RANDOM"
```

#### OData Pagination Configuration
```json
{
  "api1.http.offset.mode": "ODATA_PAGINATION",
  "api1.odata.nextlink.field": "@odata.nextLink",
  "api1.odata.deltalink.field": "@odata.deltaLink",
  "api1.odata.token.mode": "FULL_URL"
}
```

### Testing Architecture

#### Test Structure
- **Unit tests**: `src/test/java/.../unit/` - Mock-based testing with MockWebServer
- **Integration tests**: `src/test/java/.../integration/` - TestContainers with real Kafka and HTTP services
- **Base classes**: `BaseIntegrationTest.java` provides common TestContainers setup

#### Key Testing Patterns
- Use `MockWebServer` (OkHttp) for HTTP mocking, not WireMock
- TestContainers for Kafka integration with network isolation
- Real configuration objects instead of mocks for better coverage
- Dynamic encryption key generation via `KeyGenerator.getInstance("AES")`
- Security tests avoid hardcoded secrets with `// trufflehog:ignore` comments

#### Important Test Classes
- `BaseIntegrationTest`: Common TestContainers setup
- `EnterpriseConnectorIntegrationTest`: Full enterprise feature testing
- `ODataCursorPaginationTest`: Microsoft OData API pagination testing

## Project-Specific Conventions

### Error Handling Strategy
The connector categorizes errors into distinct types with different handling:
1. **Authentication errors** (401/403): Circuit breaker opens immediately
2. **Rate limit errors** (429): Exponential backoff with jitter
3. **Transient errors** (5xx, timeouts): Retry with configurable thresholds

### JSON Data Extraction
Uses custom `JsonPointer` utility (not javax.json) for extracting data from API responses:
```java
Object data = JsonPointer.extract(responseBody, "/data/users");
```

### Configuration Validation
Multi-level validation approach:
1. ConfigDef built-in validation (types, ranges, enums)
2. Cross-field validation in `validateConfiguration()`
3. Runtime validation during task startup

### Security Best Practices
- No hardcoded secrets (use dynamic generation in tests)
- Field-level encryption with AES-GCM for sensitive data
- TruffleHog comments for intentional test keys: `// trufflehog:ignore`
- OAuth2 token refresh via `ScheduledExecutorService`

## Dependencies and Integration

### Key Dependencies
- **Kafka Connect**: 3.7.0 (API compatibility)
- **OkHttp**: 4.12.0 (HTTP client with connection pooling)
- **Jackson**: 2.16.1 (JSON processing)
- **TestContainers**: 1.19.3 (integration testing)
- **Confluent Platform**: 7.6.0 (Schema Registry integration)

### Schema Registry Support
Supports AVRO, JSON_SR, and Protobuf formats. Validates schema compatibility but doesn't enforce Schema Registry presence for JSON_SR format.

### Performance Features
- Response caching with TTL (in-memory `ConcurrentHashMap`)
- Adaptive polling intervals based on API response patterns
- Connection pooling via OkHttp client factory
- HTTP/2 support with async processing

## Common Development Gotchas

### Configuration Indexing
- API indices are 1-based (api1, api2, etc.) but internal arrays are 0-based
- Configuration validation happens at both connector and task level
- Field encryption rules are per-connector, not per-API

### Testing Requirements
- Circuit breaker state is per-API but error handler is shared
- TestContainers require Docker daemon running for integration tests
- Integration tests may require significant memory for containers

### API Chaining Dependencies
- Parent APIs must be configured before child APIs
- Chaining metadata is stored in a separate Kafka topic
- Child API scheduling depends on parent API completion

## Configuration Examples
The `examples/` directory contains complete configuration samples:
- `simple-api-config.json`: Basic GET API with authentication
- `oauth2-api-config.json`: OAuth2 client credentials flow
- `api-chaining-config.json`: Parent-child API relationships
- `enterprise-features-config.json`: Full enterprise features enabled
- `odata-pagination-config.json`: Microsoft Dynamics 365 OData API with full URL mode
- `odata-token-only-config.json`: SharePoint OData API with token-only mode
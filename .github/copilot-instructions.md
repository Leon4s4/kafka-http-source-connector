# Copilot Instructions: Kafka HTTP Source Connector

## Project Overview
This is a production-ready Kafka Connect source connector that ingests data from HTTP/HTTPS APIs. The connector supports up to 15 API endpoints with enterprise features including field-level encryption, API chaining, circuit breakers, and advanced error handling.

## Architecture & Key Components

### Core Classes
- **`HttpSourceConnector`**: Main connector class, handles configuration validation and task distribution
- **`HttpSourceTask`**: Worker tasks that poll APIs and produce Kafka records. Each task manages multiple API endpoints with individual scheduling
- **`HttpSourceConnectorConfig`**: Central configuration with 70+ properties using Kafka Connect's ConfigDef pattern

### Multi-API Pattern
The connector uses a unique pattern where each connector instance can manage up to 15 APIs:
- Configuration uses indexed properties: `api1.http.api.path`, `api2.http.api.path`, etc.
- Task distribution: APIs are round-robin distributed across tasks via `task.api.indices` property
- Each API has independent offset management, authentication, and scheduling

### Key Architectural Components
- **Authentication**: Factory pattern in `auth/` package (Basic, Bearer, OAuth2, API Key)
- **API Chaining**: Parent-child relationships managed by `ApiChainingManager` - child APIs use parent response data
- **Field Encryption**: AES-GCM encryption with configurable rules per API endpoint
- **Circuit Breakers**: Per-API failure thresholds to prevent cascading failures
- **Offset Management**: Pluggable offset strategies (simple incrementing, cursor pagination, timestamp-based)

## Configuration Patterns

### API Configuration Structure
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

### API Chaining Format
```
"api.chaining.parent.child.relationship": "child_api:parent_api,child2:parent_api"
```

### Field Encryption Rules
```
"field.encryption.rules": "ssn:AES_GCM,salary:DETERMINISTIC,notes:RANDOM"
```

## Development Workflows

### Build Commands
```bash
# Standard build
mvn clean package

# Run unit tests only (excludes integration tests)
mvn test -Dtest="*Test" -Dtest.exclude="*IntegrationTest"

# Run integration tests (requires Docker for TestContainers)
mvn test -Dtest="*IntegrationTest"

# Full verification with security scans
mvn verify -Powasp-check
```

### Test Architecture
- **Unit tests**: Mock-based testing in `src/test/java/.../unit/`
- **Integration tests**: TestContainers with MockWebServer in `src/test/java/.../integration/`
- Security tests generate encryption keys dynamically to avoid hardcoded secrets
- Tests use `@Order` annotations for predictable execution

### Key Testing Patterns
- Use `MockWebServer` for HTTP mocking (not WireMock)
- TestContainers for Kafka integration with network isolation
- Real configuration objects instead of mocks for better coverage
- Test encryption keys generated via `KeyGenerator.getInstance("AES")`

## Project-Specific Conventions

### Error Handling Strategy
The connector categorizes errors into three types:
1. **Authentication errors**: Circuit breaker opens immediately
2. **Rate limit errors**: Exponential backoff with jitter
3. **Transient errors**: Retry with configurable thresholds

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
- TruffleHog comments to mark intentional test keys: `// trufflehog:ignore`
- OAuth2 token refresh scheduled via `ScheduledExecutorService`

## Dependencies & Integration Points

### External Dependencies
- **Kafka Connect**: 3.7.0 (API compatibility layer)
- **OkHttp**: 4.12.0 (HTTP client with connection pooling)
- **Jackson**: 2.16.1 (JSON processing)
- **TestContainers**: 1.19.3 (integration testing)

### Schema Registry Integration
Supports AVRO, JSON_SR, and Protobuf formats. The connector validates schema compatibility but doesn't enforce Schema Registry presence for JSON_SR.

### Performance Features
- Response caching with TTL (in-memory `ConcurrentHashMap`)
- Adaptive polling intervals based on API response patterns
- Connection pooling via OkHttp client factory

## Critical Files for Understanding
- `HttpSourceConnectorConfig.java` - All configuration properties and validation
- `HttpSourceTask.java` - Main polling logic and task coordination
- `ApiChainingManager.java` - Parent-child API relationship management
- Integration test examples show real-world usage patterns
- `examples/` directory contains complete configuration samples

## Common Gotchas
- API indices are 1-based (api1, api2, etc.) but internal arrays are 0-based
- Configuration validation happens at both connector and task level
- Field encryption rules are per-connector, not per-API
- Circuit breaker state is per-API but error handler is shared
- TestContainers require Docker daemon running for integration tests

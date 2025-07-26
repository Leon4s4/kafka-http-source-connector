# Kafka HTTP Source Connector

A production-ready Kafka Connect source connector for ingesting data from HTTP/HTTPS APIs with enterprise-grade features including field-level encryption, advanced error handling, and performance optimization.

## Features

### Core Functionality
- **Multiple API Support**: Poll up to 15 different HTTP/HTTPS endpoints
- **Authentication**: Support for None, Basic, Bearer Token, OAuth2, and API Key authentication
- **Offset Management**: Simple incrementing, cursor-based pagination, and timestamp-based offsets
- **Data Formats**: AVRO, JSON Schema Registry, and Protobuf support
- **Template Variables**: Dynamic URL construction with offset and chaining variables

### Enterprise Features (Phase 3)
- **API Chaining**: Parent-child API relationships with data dependencies
- **Field-Level Encryption**: Client-side field encryption with AES-GCM, DETERMINISTIC, and RANDOM modes
- **Advanced Error Handling**: Circuit breaker patterns with intelligent error categorization
- **Performance Optimization**: Response caching and adaptive polling intervals

### Security & Reliability
- **SSL/TLS Support**: TLSv1.3 with proper certificate validation
- **Circuit Breakers**: Prevent cascading failures with configurable thresholds
- **Error Categories**: Intelligent handling of transient, authentication, and client errors
- **Rate Limiting**: Respectful API consumption with backoff strategies

## Quick Start

### 1. Installation

```bash
# Build the connector
mvn clean package

# Copy to Kafka Connect plugins directory
cp target/kafka-http-source-connector-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
   $KAFKA_HOME/plugins/
```

### 2. Basic Configuration

```json
{
  "name": "http-source-connector",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    "tasks.max": "1",
    "http.api.base.url": "https://api.example.com/v1",
    "apis.num": "1",
    "api1.http.api.path": "/users",
    "api1.topics": "users-topic",
    "api1.http.request.method": "GET",
    "api1.http.offset.mode": "SIMPLE_INCREMENTING",
    "api1.http.initial.offset": "0",
    "api1.request.interval.ms": "30000",
    "output.data.format": "JSON_SR"
  }
}
```

### 3. Deploy Connector

```bash
# Deploy to Kafka Connect
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @basic-config.json
```

## Configuration Reference

### Connection Settings

| Property | Description | Default | Required |
|----------|-------------|---------|----------|
| `http.api.base.url` | Base URL for HTTP API | None | Yes |
| `auth.type` | Authentication type (NONE, BASIC, BEARER, OAUTH2, API_KEY) | NONE | No |
| `https.ssl.enabled` | Enable SSL/TLS validation | false | No |
| `https.ssl.protocol` | SSL protocol version | TLSv1.3 | No |

### API Configuration

| Property | Description | Default | Required |
|----------|-------------|---------|----------|
| `apis.num` | Number of APIs to configure (1-15) | 1 | Yes |
| `api{N}.http.api.path` | API endpoint path | None | Yes |
| `api{N}.topics` | Kafka topic for this API | None | Yes |
| `api{N}.http.request.method` | HTTP method (GET, POST) | GET | No |
| `api{N}.http.offset.mode` | Offset management mode | SIMPLE_INCREMENTING | No |
| `api{N}.request.interval.ms` | Polling interval in milliseconds | 30000 | No |

### Authentication

#### OAuth2 Configuration
```json
{
  "auth.type": "OAUTH2",
  "oauth2.token.url": "https://auth.example.com/oauth/token",
  "oauth2.client.id": "your-client-id",
  "oauth2.client.secret": "your-client-secret",
  "oauth2.client.scope": "read:data",
  "oauth2.token.refresh.interval.minutes": "20"
}
```

#### API Key Configuration
```json
{
  "auth.type": "API_KEY",
  "api.key.location": "HEADER",
  "api.key.name": "X-API-KEY",
  "api.key.value": "your-api-key"
}
```

### Enterprise Features

#### Field-Level Encryption
```json
{
  "field.encryption.enabled": true,
  "field.encryption.key": "base64-encoded-256-bit-key",
  "field.encryption.rules": "ssn:AES_GCM,salary:DETERMINISTIC,notes:RANDOM"
}
```

#### Circuit Breaker
```json
{
  "circuit.breaker.failure.threshold": 3,
  "circuit.breaker.timeout.ms": 60000,
  "circuit.breaker.recovery.time.ms": 30000
}
```

#### Performance Optimization
```json
{
  "response.caching.enabled": true,
  "response.cache.ttl.ms": 300000,
  "max.cache.size": 1000,
  "adaptive.polling.enabled": true
}
```

## Advanced Usage

### API Chaining

Configure parent-child API relationships where child APIs depend on data from parent APIs:

```json
{
  "apis.num": "2",
  "api.chaining.parent.child.relationship": "api2:api1",
  
  "api1.http.api.path": "/companies",
  "api1.topics": "companies-topic",
  "api1.http.response.data.json.pointer": "/companies",
  
  "api2.http.api.path": "/companies/${parent_value}/employees",
  "api2.topics": "employees-topic",
  "api2.http.chaining.json.pointer": "/id",
  "api2.http.response.data.json.pointer": "/employees"
}
```

### Pagination Support

#### Cursor-based Pagination
```json
{
  "api1.http.offset.mode": "CURSOR_PAGINATION",
  "api1.http.next.page.json.pointer": "/pagination/next_cursor",
  "api1.http.response.data.json.pointer": "/data"
}
```

#### Timestamp-based Pagination
```json
{
  "api1.http.offset.mode": "TIMESTAMP_PAGINATION",
  "api1.http.timestamp.json.pointer": "/last_modified",
  "api1.http.timestamp.format": "yyyy-MM-dd'T'HH:mm:ss'Z'"
}
```

### Template Variables

Use template variables in URLs, headers, and parameters:

```json
{
  "api1.http.api.path": "/data?since=${offset}&limit=100",
  "api1.http.request.headers": "X-Request-ID: ${uuid}",
  "api1.http.request.parameters": "timestamp=${current_time}"
}
```

## Monitoring and Troubleshooting

### Key Metrics

The connector exposes metrics for monitoring:

- **Records per second**: Throughput measurement
- **Error rates**: By category (transient, authentication, etc.)
- **Circuit breaker state**: Open/closed status per API
- **Cache hit rates**: Performance optimization effectiveness
- **Response times**: API latency monitoring

### Common Issues

#### Authentication Failures
```
ERROR Authentication error for API api1: 401 Unauthorized
```
**Solution**: Verify credentials and token expiration

#### Circuit Breaker Activation
```
ERROR Circuit breaker OPENED for API api1 after 3 failures
```
**Solution**: Check API health and adjust failure thresholds

#### Memory Usage
- Monitor heap usage with large payloads
- Adjust cache sizes based on available memory
- Use field encryption selectively for performance

### Debug Configuration

Enable detailed logging:
```json
{
  "behavior.on.error": "FAIL",
  "reporter.error.topic.name": "connector-errors",
  "report.errors.as": "http_response"
}
```

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests (requires Docker)
```bash
mvn verify -Pfailsafe
```

### Performance Benchmarks
```bash
mvn test -Dtest=PerformanceBenchmarkTest
```

### Security Audit
```bash
mvn test -Dtest=SecurityAuditTest
```

## Security Considerations

### Encryption
- Use 256-bit AES keys for field encryption
- Store encryption keys securely (KMS recommended)
- Rotate keys regularly per organizational policy

### Authentication
- Use OAuth2 or API keys over basic authentication
- Enable HTTPS for all connections
- Validate SSL certificates in production

### Network Security
- Configure proxy settings if required
- Use TLSv1.3 for secure connections
- Monitor for unusual traffic patterns

## Performance Tuning

### Throughput Optimization
- Enable response caching for repeated data
- Use multiple tasks for parallel processing
- Adjust polling intervals based on data freshness needs

### Memory Management
- Set appropriate cache sizes
- Monitor heap usage with large payloads
- Use selective field encryption

### Network Optimization
- Configure appropriate timeouts
- Enable compression when supported
- Use connection pooling for efficiency

## Migration Guide

### From Version 1.x
- Review authentication configuration changes
- Update field encryption rules format
- Verify circuit breaker threshold values

### Configuration Validation
Use the configuration validation endpoint:
```bash
curl -X PUT http://localhost:8083/connector-plugins/HttpSourceConnector/config/validate \
  -H "Content-Type: application/json" \
  -d @your-config.json
```

## Support and Contributing

### Issues
Report issues at: https://github.com/your-org/kafka-http-source-connector/issues

### Documentation
Complete documentation available at: [Enterprise Features Guide](./ENTERPRISE_FEATURES.md)

### Contributing
1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## License

Licensed under the Apache License 2.0. See LICENSE file for details.

## Changelog

### Version 1.0.0
- Initial release with core HTTP source functionality
- OAuth2, API Key, and Basic authentication support
- Field-level encryption with AES-GCM
- Circuit breaker error handling
- Response caching and adaptive polling
- API chaining for dependent endpoints
- Comprehensive test suite with TestContainers
- Performance benchmarking and security audit
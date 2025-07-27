# Enterprise Features Guide

This document covers the advanced enterprise features implemented in the HTTP Source Connector, including field-level encryption, advanced error handling, and performance optimizations.

## Table of Contents

1. [Field-Level Encryption](#field-level-encryption)
2. [Advanced Error Handling](#advanced-error-handling)
3. [Performance Optimization](#performance-optimization)
4. [Complete Configuration Example](#complete-configuration-example)

## Field-Level Encryption

### Overview

The connector supports Client-Side Field Level Encryption (CSFLE) to protect sensitive data before it's written to Kafka topics. This ensures that sensitive information like SSNs, financial data, and personal information remain encrypted throughout the entire data pipeline.

### Configuration

```json
{
  "field.encryption.enabled": true,
  "field.encryption.key": "base64-encoded-256-bit-key",
  "field.encryption.rules": "field1:AES_GCM,api2.field2:DETERMINISTIC,field3:RANDOM"
}
```

### Encryption Types

#### AES_GCM (Recommended)
- **Security**: Highest security with authentication
- **Characteristics**: Random IV, authenticated encryption
- **Use case**: Maximum security for highly sensitive data
- **Note**: Same plaintext produces different ciphertext each time

```json
"field.encryption.rules": "ssn:AES_GCM,credit_card:AES_GCM"
```

#### DETERMINISTIC
- **Security**: High security, deterministic
- **Characteristics**: Same plaintext always produces same ciphertext  
- **Use case**: When you need to search/join on encrypted fields
- **Note**: Enables equality searches but reveals duplicate values

```json
"field.encryption.rules": "customer_id:DETERMINISTIC,account_number:DETERMINISTIC"
```

#### RANDOM
- **Security**: High security, non-deterministic
- **Characteristics**: Same as AES_GCM
- **Use case**: Alias for AES_GCM encryption

```json
"field.encryption.rules": "personal_notes:RANDOM"
```

### Field Path Specification

Fields can be specified with API-specific paths:

```json
{
  "field.encryption.rules": "salary:AES_GCM,api2.employee_id:DETERMINISTIC,api3.revenue:AES_GCM"
}
```

- `salary` - Encrypts 'salary' field in all APIs
- `api2.employee_id` - Encrypts 'employee_id' field only in API #2
- `api3.revenue` - Encrypts 'revenue' field only in API #3

### Key Management

#### Auto-Generated Keys
If no key is provided, the connector generates a new AES-256 key:

```json
{
  "field.encryption.enabled": true,
  "field.encryption.key": null
}
```

⚠️ **Warning**: Save the generated key from the logs for data recovery!

#### Pre-Shared Keys
Provide your own base64-encoded AES-256 key:

```bash
# Generate a key
openssl rand -base64 32
# Example output: "7x8y9z0a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u="
```

```json
{
  "field.encryption.key": "7x8y9z0a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u="
}
```

### Data Format Examples

#### Before Encryption
```json
{
  "employee_id": "EMP001",
  "name": "John Doe",
  "salary": 75000,
  "ssn": "123-45-6789"
}
```

#### After Encryption
```json
{
  "employee_id": "EMP001",
  "name": "John Doe", 
  "salary": "A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q7R8S9T0U1V2W3X4Y5Z6",
  "ssn": "Z6Y5X4W3V2U1T0S9R8Q7P6O5N4M3L2K1J0I9H8G7F6E5D4C3B2A1"
}
```

## Advanced Error Handling

### Circuit Breaker Pattern

The connector implements circuit breaker patterns to prevent cascading failures and improve system resilience.

#### Configuration

```json
{
  "circuit.breaker.failure.threshold": 5,
  "circuit.breaker.timeout.ms": 60000,
  "circuit.breaker.recovery.time.ms": 30000
}
```

#### Circuit Breaker States

1. **CLOSED** (Normal Operation)
   - All requests pass through
   - Failure counter tracks consecutive failures

2. **OPEN** (Failing Fast)
   - Requests fail immediately without calling API
   - Triggered when failure count exceeds threshold
   - Prevents further system stress

3. **HALF_OPEN** (Testing Recovery)
   - Single test request allowed after recovery time
   - Success closes circuit, failure reopens it

### Error Categories

The system categorizes errors for intelligent handling:

#### Transient Errors (Retriable)
- Network timeouts
- HTTP 5xx errors
- Connection failures
- **Action**: Retry with backoff, count toward circuit breaker

#### Authentication Errors
- HTTP 401, 403 errors
- Invalid credentials
- **Action**: Log error, don't retry, don't count toward circuit breaker

#### Client Errors
- HTTP 4xx errors (except auth)
- Malformed requests
- **Action**: Log warning, don't retry

#### Rate Limiting
- HTTP 429 errors
- **Action**: Retry with increased backoff

#### Configuration Errors
- Invalid settings
- Missing required parameters
- **Action**: Fail fast, manual intervention required

#### Data Format Errors
- JSON parsing failures
- Schema validation errors
- **Action**: Log warning, continue processing

### Error Metrics

The system tracks comprehensive error metrics:

```java
ErrorMetrics metrics = advancedErrorHandler.getErrorMetrics("api1");
double errorRate = metrics.getErrorRate(); // Percentage of failed requests
long timeSinceLastError = metrics.getTimeSinceLastError(); // Milliseconds
Map<ErrorCategory, AtomicInteger> errorsByCategory = metrics.errorsByCategory;
```

### Configuration Examples

#### High-Sensitivity Environment
```json
{
  "circuit.breaker.failure.threshold": 2,
  "circuit.breaker.timeout.ms": 30000,
  "circuit.breaker.recovery.time.ms": 10000,
  "behavior.on.error": "FAIL"
}
```

#### High-Availability Environment  
```json
{
  "circuit.breaker.failure.threshold": 10,
  "circuit.breaker.timeout.ms": 120000,
  "circuit.breaker.recovery.time.ms": 60000,
  "behavior.on.error": "IGNORE"
}
```

## Performance Optimization

### Response Caching

Intelligent caching reduces API calls and improves throughput.

#### Configuration

```json
{
  "response.caching.enabled": true,
  "response.cache.ttl.ms": 300000,
  "max.cache.size": 1000
}
```

#### Cache Behavior

- **Cache Keys**: Based on API ID, URL, method, and parameters
- **HTTP Headers**: Respects `Cache-Control` and `Pragma` headers
- **TTL**: Configurable time-to-live per response
- **Eviction**: LRU eviction when cache is full
- **Background Cleanup**: Automatic expired entry removal

#### Cache Metrics

```java
PerformanceMetrics metrics = performanceOptimizer.getPerformanceMetrics("api1");
double cacheHitRate = metrics.getCacheHitRate(); // Percentage of cache hits
double avgResponseTime = metrics.getAverageResponseTime(); // Milliseconds
```

### Adaptive Polling

Dynamically adjusts polling intervals based on API response patterns.

#### Configuration

```json
{
  "adaptive.polling.enabled": true
}
```

#### Adaptive Behavior

1. **Data Available**: Decreases interval by 20% (minimum: configured_interval ÷ 4)
2. **No Data**: Increases interval by 50% after 3 consecutive empty polls (maximum: configured_interval × 4)

#### Benefits

- **Reduced API Load**: Less frequent polling when no new data
- **Improved Latency**: More frequent polling when data is available
- **Resource Efficiency**: Optimizes network and CPU usage

### Performance Monitoring

#### Automatic Metrics Collection

The system automatically logs performance metrics every 60 seconds:

```
INFO - Performance metrics for API api1: total calls=1250, cache hit rate=73.2%, avg response time=245.3ms
```

#### Manual Metrics Access

```java
PerformanceOptimizer.PerformanceMetrics metrics = performanceOptimizer.getPerformanceMetrics("api1");

// Cache performance
double cacheHitRate = metrics.getCacheHitRate();
long cacheHits = metrics.cacheHits.get();
long cacheMisses = metrics.cacheMisses.get();

// Response performance  
double avgResponseTime = metrics.getAverageResponseTime();
long maxResponseTime = metrics.maxResponseTime.get();

// Success rate
double successRate = metrics.getSuccessRate();
```

## Complete Configuration Example

Here's a comprehensive example showcasing all enterprise features:

```json
{
  "name": "enterprise-http-source-connector",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    "tasks.max": "3",
    "http.api.base.url": "https://api.enterprise.com/v1",
    
    "auth.type": "OAUTH2",
    "oauth2.token.url": "https://auth.enterprise.com/oauth/token",
    "oauth2.client.id": "enterprise-client-id",
    "oauth2.client.secret": "enterprise-client-secret",
    "oauth2.client.scope": "read:data write:metrics",
    "oauth2.token.refresh.interval.minutes": "20",
    
    "apis.num": "3",
    "api.chaining.parent.child.relationship": "api2:api1,api3:api1",
    
    "api1.http.api.path": "/companies",
    "api1.topics": "companies-topic",
    "api1.http.request.method": "GET",
    "api1.http.offset.mode": "CURSOR_PAGINATION",
    "api1.http.next.page.json.pointer": "/pagination/next_cursor",
    "api1.http.response.data.json.pointer": "/companies",
    "api1.request.interval.ms": "30000",
    
    "api2.http.api.path": "/companies/${parent_value}/employees",
    "api2.topics": "employees-topic",
    "api2.http.request.method": "GET",
    "api2.http.offset.mode": "SIMPLE_INCREMENTING",
    "api2.http.initial.offset": "0",
    "api2.http.chaining.json.pointer": "/id",
    "api2.http.response.data.json.pointer": "/employees",
    "api2.request.interval.ms": "15000",
    
    "api3.http.api.path": "/companies/${parent_value}/financial-data",
    "api3.topics": "financial-data-topic",
    "api3.http.request.method": "GET",
    "api3.http.offset.mode": "SIMPLE_INCREMENTING",
    "api3.http.initial.offset": "0",
    "api3.http.chaining.json.pointer": "/id",
    "api3.http.response.data.json.pointer": "/financial_records",
    "api3.request.interval.ms": "60000",
    
    "output.data.format": "AVRO",
    "schema.context.name": "enterprise-context",
    "value.subject.name.strategy": "TopicNameStrategy",
    
    "field.encryption.enabled": true,
    "field.encryption.key": "your-base64-encoded-256-bit-key-here",
    "field.encryption.rules": "salary:AES_GCM,api3.revenue:DETERMINISTIC,ssn:AES_GCM,api3.profit_margin:RANDOM",
    
    "circuit.breaker.failure.threshold": "3",
    "circuit.breaker.timeout.ms": "45000",
    "circuit.breaker.recovery.time.ms": "20000",
    
    "response.caching.enabled": true,
    "response.cache.ttl.ms": "180000",
    "max.cache.size": "500",
    "adaptive.polling.enabled": true,
    
    "behavior.on.error": "IGNORE",
    "reporter.error.topic.name": "enterprise-connector-errors",
    "report.errors.as": "http_response",
    
    "https.ssl.enabled": true,
    "https.ssl.protocol": "TLSv1.3"
  }
}
```

## Security Considerations

### Field-Level Encryption
- Store encryption keys securely (Key Management Service recommended)
- Rotate keys regularly following your organization's policy  
- Use DETERMINISTIC encryption only when search capability is required
- Monitor for key exposure in logs or configuration files

### Error Handling
- Set appropriate circuit breaker thresholds for your SLA requirements
- Monitor error patterns for potential security issues
- Configure dead letter queues for sensitive data appropriately

### Performance Optimization  
- Cache TTL should align with data sensitivity requirements
- Monitor cache hit rates and adjust cache size accordingly
- Adaptive polling reduces API fingerprinting but may impact data freshness

## Monitoring and Alerting

### Key Metrics to Monitor

1. **Circuit Breaker State Changes**
   - Alert when circuits open/close frequently
   - Monitor recovery success rates

2. **Encryption Failures**
   - Alert on any encryption/decryption errors
   - Monitor for key-related issues

3. **Performance Metrics**
   - Cache hit rates below threshold
   - Response times exceeding SLA
   - Adaptive polling interval changes

4. **Error Categories**
   - Spikes in authentication errors
   - Unusual data format errors
   - Configuration error patterns

### Log Patterns for Monitoring

```bash
# Circuit breaker events
grep "Circuit breaker" /var/log/kafka-connect/connector.log

# Encryption issues  
grep "Failed to encrypt\|encryption error" /var/log/kafka-connect/connector.log

# Performance metrics
grep "Performance metrics" /var/log/kafka-connect/connector.log

# Error categorization
grep "Authentication error\|Configuration error" /var/log/kafka-connect/connector.log
```

This enterprise feature set provides production-ready capabilities for secure, resilient, and high-performance HTTP data ingestion into Kafka.
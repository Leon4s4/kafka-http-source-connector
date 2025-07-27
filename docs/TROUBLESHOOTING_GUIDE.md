# Troubleshooting Guide

This comprehensive troubleshooting guide helps diagnose and resolve common issues with the Kafka HTTP Source Connector.

## Table of Contents
1. [Quick Diagnostic Checklist](#quick-diagnostic-checklist)
2. [Authentication Issues](#authentication-issues)
3. [Connection Problems](#connection-problems)
4. [Performance Issues](#performance-issues)
5. [Data Processing Issues](#data-processing-issues)
6. [Configuration Problems](#configuration-problems)
7. [Monitoring and Debugging](#monitoring-and-debugging)
8. [Error Codes Reference](#error-codes-reference)
9. [Common Patterns and Solutions](#common-patterns-and-solutions)

## Quick Diagnostic Checklist

### First Steps
1. **Check Health Endpoints** (if enabled)
   ```bash
   curl http://localhost:8080/health
   curl http://localhost:8080/health/auth
   curl http://localhost:8080/health/apis
   ```

2. **Review JMX Metrics** (if enabled)
   ```bash
   # Check error counts
   jconsole localhost:9999
   # Look for: kafka.connect.http:type=HttpSourceConnector,name=ErrorCount
   ```

3. **Check Connector Status**
   ```bash
   curl http://localhost:8083/connectors/your-connector-name/status
   ```

4. **Review Logs**
   ```bash
   # Look for recent errors
   tail -f /path/to/connect.log | grep -i error
   
   # Check connector-specific logs
   tail -f /path/to/connect.log | grep "HttpSourceConnector"
   ```

### Common Quick Fixes
- Restart the connector: `curl -X POST http://localhost:8083/connectors/your-connector-name/restart`
- Check network connectivity: `curl -v https://your-api-endpoint.com`
- Verify authentication credentials are current
- Ensure Kafka topics exist and are accessible

## Authentication Issues

### OAuth2 Token Problems

#### Symptom: 401 Unauthorized errors
```
ERROR [HttpSourceTask] Authentication failed: 401 Unauthorized
```

**Diagnosis:**
1. Check token expiration:
   ```bash
   # Decode JWT token to check expiry
   echo "your-jwt-token" | cut -d. -f2 | base64 -d | jq .exp
   ```

2. Verify token refresh configuration:
   ```properties
   http.auth.oauth2.token.refresh.enabled=true
   http.auth.oauth2.token.refresh.buffer.seconds=300
   ```

**Solutions:**
- Enable automatic token refresh
- Increase refresh buffer time
- Verify client credentials are correct
- Check OAuth2 scope permissions

#### Symptom: Token refresh failures
```
ERROR [OAuth2Authenticator] Failed to refresh token: invalid_client
```

**Solutions:**
```properties
# Ensure correct token endpoint
http.auth.oauth2.token.url=https://correct-endpoint.com/oauth/token

# Verify grant type
http.auth.oauth2.grant.type=client_credentials

# Check client credentials format
http.auth.oauth2.client.id=${env:CLIENT_ID}
http.auth.oauth2.client.secret=${env:CLIENT_SECRET}
```

### Vault Authentication Issues

#### Symptom: Vault connection failures
```
ERROR [VaultAuthenticator] Failed to connect to Vault: connection timeout
```

**Diagnosis:**
1. Test Vault connectivity:
   ```bash
   curl -v https://vault.example.com/v1/sys/health
   ```

2. Verify token permissions:
   ```bash
   vault auth -method=token token=your-token
   vault read secret/api-credentials
   ```

**Solutions:**
```properties
# Increase connection timeout
http.auth.vault.connection.timeout.ms=30000

# Enable token renewal
http.auth.vault.renewal.enabled=true
http.auth.vault.renewal.threshold=300

# Verify Vault address
http.auth.vault.address=https://vault.example.com
```

### AWS IAM Authentication Issues

#### Symptom: Signature validation errors
```
ERROR [AwsIamAuthenticator] AWS signature validation failed
```

**Solutions:**
```properties
# Ensure correct region
http.auth.aws.region=us-west-2

# Verify service name
http.auth.aws.service=execute-api

# Check credentials
http.auth.aws.access.key.id=${env:AWS_ACCESS_KEY_ID}
http.auth.aws.secret.access.key=${env:AWS_SECRET_ACCESS_KEY}

# Use session tokens if required
http.auth.aws.session.token=${env:AWS_SESSION_TOKEN}
```

## Connection Problems

### SSL/TLS Issues

#### Symptom: Certificate validation failures
```
ERROR [HttpClient] SSL handshake failed: certificate validation error
```

**Diagnosis:**
1. Test SSL connection:
   ```bash
   openssl s_client -connect api.example.com:443 -verify_return_error
   ```

2. Check certificate chain:
   ```bash
   curl -vI https://api.example.com
   ```

**Solutions:**
```properties
# For development environments
ssl.validation.level=RELAXED

# For production with custom certificates
ssl.truststore.location=/path/to/truststore.jks
ssl.truststore.password=truststore-password

# For certificate pinning
ssl.certificate.pinning.enabled=true
ssl.certificate.pins=sha256:CERTIFICATE_HASH
```

### Network Connectivity Issues

#### Symptom: Connection timeouts
```
ERROR [HttpClient] Connection timeout after 30000ms
```

**Solutions:**
```properties
# Increase timeouts
http.client.connection.timeout.ms=60000
http.client.read.timeout.ms=120000

# Enable connection pooling
http.client.connection.pool.enabled=true
http.client.max.connections=20
http.client.max.connections.per.route=10
```

### Rate Limiting Issues

#### Symptom: 429 Too Many Requests
```
ERROR [HttpClient] Rate limit exceeded: 429 Too Many Requests
```

**Solutions:**
```properties
# Implement conservative rate limiting
rate.limit.enabled=true
rate.limit.requests.per.second=5
rate.limit.algorithm=TOKEN_BUCKET
rate.limit.burst.capacity=10

# Add backoff strategy
rate.limit.backoff.enabled=true
rate.limit.backoff.initial.delay.ms=1000
rate.limit.backoff.max.delay.ms=60000
rate.limit.backoff.multiplier=2.0
```

## Performance Issues

### High Memory Usage

#### Symptom: OutOfMemoryError
```
ERROR [HttpSourceTask] java.lang.OutOfMemoryError: Java heap space
```

**Diagnosis:**
1. Check JVM memory settings:
   ```bash
   jps -v | grep ConnectDistributed
   ```

2. Monitor heap usage:
   ```bash
   jstat -gc -t <connect-process-id> 5s
   ```

**Solutions:**
```properties
# Reduce response size limits
http.client.max.response.size.bytes=5242880  # 5MB

# Optimize caching
cache.response.max.size=500
cache.schema.max.size=50

# Reduce batch sizes
transformation.batch.size=50
pagination.limit.value=50
```

### Slow Performance

#### Symptom: High latency and low throughput
```
WARN [HttpSourceTask] Average response time: 5000ms, throughput: 10 records/sec
```

**Solutions:**
```properties
# Increase parallelism
tasks.max=4

# Optimize polling
http.source.poll.interval.ms=30000

# Enable caching
cache.enabled=true
cache.response.ttl.seconds=3600

# Use connection pooling
http.client.connection.pool.enabled=true
```

### Cache Performance Issues

#### Symptom: Low cache hit ratio
```
INFO [CacheManager] Cache hit ratio: 15%
```

**Solutions:**
```properties
# Increase cache sizes
cache.response.max.size=2000
cache.schema.max.size=200

# Optimize TTL
cache.response.ttl.seconds=7200
cache.schema.ttl.seconds=14400

# Enable cache statistics
cache.statistics.enabled=true
```

## Data Processing Issues

### Schema Validation Errors

#### Symptom: Schema compatibility issues
```
ERROR [SchemaValidator] Schema validation failed: field type mismatch
```

**Solutions:**
```properties
# Enable schema caching
cache.schema.enabled=true
cache.schema.ttl.seconds=7200

# Configure transformation mapping
transformation.field.mappings=[
  {"source": "timestamp", "target": "event_time", "type": "TIMESTAMP"},
  {"source": "amount", "target": "value", "type": "DOUBLE"}
]

# Add validation rules
transformation.validation.rules=[
  {"field": "event_time", "required": true},
  {"field": "value", "type": "NUMERIC", "min": 0}
]
```

### Data Transformation Errors

#### Symptom: Transformation failures
```
ERROR [TransformationEngine] Failed to apply transformation: field not found
```

**Solutions:**
```properties
# Add null value handling
transformation.null.value.handling=SKIP

# Use conditional expressions
transformation.field.mappings=[
  {"source": "optional_field", "target": "target_field", "type": "STRING", "default": "N/A"}
]

# Enable debug logging
transformation.debug.enabled=true
```

### Pagination Issues

#### Symptom: Incomplete data retrieval
```
WARN [PaginationManager] Pagination stopped early: max pages reached
```

**Solutions:**
```properties
# Increase page limits
pagination.max.pages=1000

# Optimize page size
pagination.limit.value=100

# Use appropriate pagination strategy
pagination.type=cursor  # For APIs with cursor-based pagination
pagination.cursor.field=next_page_token
```

## Configuration Problems

### Invalid Configuration Values

#### Symptom: Connector fails to start
```
ERROR [HttpSourceConnector] Invalid configuration: required property missing
```

**Common Fixes:**
```properties
# Ensure required properties are set
http.source.url=https://api.example.com/data
kafka.topic=my-topic

# Check data types
http.source.poll.interval.ms=60000  # Must be numeric
tasks.max=2  # Must be integer

# Validate authentication config
http.auth.type=oauth2  # Must be valid auth type
```

### Environment Variable Issues

#### Symptom: Configuration values not resolved
```
ERROR [ConfigProvider] Failed to resolve environment variable: ${env:API_KEY}
```

**Solutions:**
```bash
# Verify environment variables exist
echo $API_KEY

# Use different variable providers
http.auth.api.key=${file:/path/to/secret}
http.auth.oauth2.client.secret=${vault:secret/oauth:client_secret}
```

## Monitoring and Debugging

### Enable Debug Logging

```properties
# Add to log4j.properties
log4j.logger.io.confluent.connect.http=DEBUG, stdout
log4j.logger.io.confluent.connect.http.auth=DEBUG, stdout
log4j.logger.io.confluent.connect.http.client=DEBUG, stdout
```

### JMX Monitoring Setup

```properties
# Enable JMX in connector config
jmx.enabled=true
jmx.domain=kafka.connect.http
jmx.object.name=type=HttpSourceConnector,name=${connector.name}

# Connect with JConsole
# jconsole localhost:9999
```

### Health Check Monitoring

```bash
#!/bin/bash
# Health check script
HEALTH_URL="http://localhost:8080/health"

response=$(curl -s -o /dev/null -w "%{http_code}" $HEALTH_URL)
if [ $response -eq 200 ]; then
    echo "Connector healthy"
else
    echo "Connector unhealthy: HTTP $response"
    # Alert or restart logic here
fi
```

## Error Codes Reference

### HTTP Status Codes

| Code | Meaning | Common Causes | Solutions |
|------|---------|---------------|-----------|
| 400 | Bad Request | Invalid query parameters | Check URL parameters and encoding |
| 401 | Unauthorized | Authentication failure | Verify credentials and token validity |
| 403 | Forbidden | Insufficient permissions | Check API key permissions and scopes |
| 404 | Not Found | Invalid endpoint | Verify URL and API version |
| 429 | Too Many Requests | Rate limit exceeded | Implement rate limiting and backoff |
| 500 | Internal Server Error | API server issues | Implement retry logic and circuit breaker |
| 502 | Bad Gateway | Proxy/gateway issues | Check network connectivity |
| 503 | Service Unavailable | API maintenance | Implement retry with exponential backoff |

### Connector Error Codes

| Code | Description | Solutions |
|------|-------------|-----------|
| AUTH_001 | OAuth2 token expired | Enable token refresh |
| AUTH_002 | Invalid credentials | Verify username/password |
| AUTH_003 | Vault connection failed | Check Vault connectivity |
| CONN_001 | Connection timeout | Increase timeout values |
| CONN_002 | SSL handshake failed | Check certificate configuration |
| DATA_001 | Schema validation failed | Review field mappings |
| DATA_002 | Transformation error | Check transformation rules |
| RATE_001 | Rate limit exceeded | Adjust rate limiting settings |

## Common Patterns and Solutions

### Pattern: Intermittent Authentication Failures

**Symptoms:**
- Occasional 401 errors
- Token refresh failures
- Inconsistent authentication success

**Root Causes:**
- Clock skew between systems
- Token expiry edge cases
- Network interruptions during token refresh

**Solutions:**
```properties
# Increase token refresh buffer
http.auth.oauth2.token.refresh.buffer.seconds=600

# Enable retry logic
http.auth.retry.enabled=true
http.auth.retry.max.attempts=3
http.auth.retry.delay.ms=1000

# Add time synchronization checks
http.auth.clock.skew.tolerance.seconds=300
```

### Pattern: Data Inconsistency

**Symptoms:**
- Missing records
- Duplicate data
- Out-of-order processing

**Solutions:**
```properties
# Enable request deduplication
deduplication.enabled=true
deduplication.key.fields=id,timestamp
deduplication.window.minutes=60

# Use consistent hashing for partitioning
partitioning.strategy=CONSISTENT_HASH
partitioning.key.field=id

# Enable exactly-once semantics
enable.idempotence=true
```

### Pattern: Memory Leaks

**Symptoms:**
- Gradually increasing memory usage
- OutOfMemoryError after extended runtime
- Garbage collection issues

**Solutions:**
```properties
# Implement cache eviction
cache.eviction.policy=LRU
cache.max.size.bytes=104857600  # 100MB

# Limit response sizes
http.client.max.response.size.bytes=10485760  # 10MB

# Enable connection cleanup
http.client.connection.cleanup.enabled=true
http.client.connection.cleanup.interval.ms=300000
```

### Pattern: Network Instability

**Symptoms:**
- Frequent connection timeouts
- SSL handshake failures
- Intermittent connectivity issues

**Solutions:**
```properties
# Implement circuit breaker
circuit.breaker.enabled=true
circuit.breaker.failure.threshold=5
circuit.breaker.recovery.timeout.ms=60000

# Add retry logic
http.retry.enabled=true
http.retry.max.attempts=3
http.retry.backoff.ms=2000

# Use connection pooling
http.client.connection.pool.enabled=true
http.client.connection.pool.max.idle.time.ms=300000
```

## Advanced Debugging Techniques

### 1. Network Traffic Analysis
```bash
# Capture HTTP traffic
tcpdump -i any -s 0 -w http_traffic.pcap host api.example.com

# Analyze with Wireshark or tshark
tshark -r http_traffic.pcap -Y "http"
```

### 2. SSL/TLS Debugging
```bash
# Test SSL connection
openssl s_client -connect api.example.com:443 -debug -msg

# Verify certificate chain
openssl verify -CAfile ca-bundle.crt server.crt
```

### 3. Authentication Token Analysis
```bash
# Decode JWT token
echo "jwt-token-here" | cut -d. -f2 | base64 -d | jq .

# Test OAuth2 flow manually
curl -X POST https://api.example.com/oauth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=your-id&client_secret=your-secret"
```

### 4. Performance Profiling
```bash
# JVM profiling
java -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=profile.jfr

# Analyze with Java Mission Control
jmc profile.jfr
```

## Getting Help

### Log Collection Script
```bash
#!/bin/bash
# collect-logs.sh - Comprehensive log collection

CONNECTOR_NAME="your-connector-name"
OUTPUT_DIR="connector-debug-$(date +%Y%m%d-%H%M%S)"

mkdir -p $OUTPUT_DIR

# Collect connector status
curl -s http://localhost:8083/connectors/$CONNECTOR_NAME/status > $OUTPUT_DIR/connector-status.json

# Collect configuration
curl -s http://localhost:8083/connectors/$CONNECTOR_NAME/config > $OUTPUT_DIR/connector-config.json

# Collect recent logs
tail -1000 /path/to/connect.log | grep -i $CONNECTOR_NAME > $OUTPUT_DIR/connector-logs.txt

# Collect JVM information
jps -v > $OUTPUT_DIR/jvm-info.txt

# Collect health check (if enabled)
curl -s http://localhost:8080/health > $OUTPUT_DIR/health-check.json 2>/dev/null || echo "Health check not available"

# Create diagnostic report
echo "Diagnostic Report - $(date)" > $OUTPUT_DIR/diagnostic-report.txt
echo "====================================" >> $OUTPUT_DIR/diagnostic-report.txt
echo "Connector Name: $CONNECTOR_NAME" >> $OUTPUT_DIR/diagnostic-report.txt
echo "Collection Time: $(date)" >> $OUTPUT_DIR/diagnostic-report.txt
echo "Java Version: $(java -version 2>&1 | head -1)" >> $OUTPUT_DIR/diagnostic-report.txt

echo "Debug information collected in: $OUTPUT_DIR"
```

### Support Information Template
When reporting issues, include:

1. **Connector Configuration** (redact sensitive information)
2. **Error Messages** (full stack traces)
3. **Environment Information**
   - Java version
   - Kafka Connect version
   - Operating system
   - Network configuration
4. **Steps to Reproduce**
5. **Expected vs Actual Behavior**
6. **Timing Information**
   - When did the issue start?
   - Is it consistent or intermittent?
7. **Related Changes**
   - Recent configuration changes
   - Infrastructure changes
   - API provider changes

This troubleshooting guide covers the most common issues and provides systematic approaches to diagnosis and resolution. For complex issues, consider enabling debug logging and collecting comprehensive diagnostic information before seeking support.

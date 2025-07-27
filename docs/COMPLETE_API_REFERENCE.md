# Kafka HTTP Source Connector - Complete API Reference

## Table of Contents
1. [Overview](#overview)
2. [Configuration Reference](#configuration-reference)
3. [Authentication Configuration](#authentication-configuration)
4. [Advanced Features](#advanced-features)
5. [Monitoring and Metrics](#monitoring-and-metrics)
6. [Performance Tuning](#performance-tuning)
7. [Troubleshooting](#troubleshooting)
8. [Examples](#examples)

## Overview

The Kafka HTTP Source Connector is an enterprise-grade connector that enables real-time data ingestion from HTTP APIs into Apache Kafka. It provides comprehensive features including authentication, transformation, pagination, caching, monitoring, and enterprise security.

### Key Features
- **Multiple Authentication Methods**: OAuth2, Basic Auth, API Key, JWT, HashiCorp Vault, AWS IAM, Azure AD
- **Advanced Data Processing**: Schema transformation, field mapping, data enrichment, filtering
- **Enterprise Security**: SSL/TLS enhancements, certificate pinning, credential rotation
- **Performance Optimization**: Intelligent caching, pagination support, rate limiting
- **Operational Excellence**: JMX monitoring, health check endpoints, circuit breaker patterns
- **API Integration**: OpenAPI documentation, API chaining, request/response transformation

## Configuration Reference

### Core Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `http.source.url` | String | **Required** | Base URL for the HTTP API endpoint |
| `http.source.method` | String | `GET` | HTTP method (GET, POST, PUT, PATCH, DELETE) |
| `http.source.headers` | String | `""` | HTTP headers in key:value format, separated by semicolons |
| `http.source.body` | String | `""` | HTTP request body (for POST/PUT/PATCH requests) |
| `http.source.poll.interval.ms` | Long | `60000` | Polling interval in milliseconds |
| `kafka.topic` | String | **Required** | Target Kafka topic name |

### Authentication Configuration

#### OAuth2 Configuration
```properties
# OAuth2 Settings
http.auth.type=oauth2
http.auth.oauth2.client.id=your-client-id
http.auth.oauth2.client.secret=your-client-secret
http.auth.oauth2.token.url=https://api.example.com/oauth/token
http.auth.oauth2.scope=read,write
http.auth.oauth2.grant.type=client_credentials
http.auth.oauth2.token.refresh.enabled=true
http.auth.oauth2.token.refresh.buffer.seconds=300
```

#### Basic Authentication
```properties
http.auth.type=basic
http.auth.basic.username=your-username
http.auth.basic.password=your-password
```

#### API Key Authentication
```properties
http.auth.type=api_key
http.auth.api.key=your-api-key
http.auth.api.key.header=X-API-Key
```

#### JWT Authentication
```properties
http.auth.type=jwt
http.auth.jwt.token=your-jwt-token
http.auth.jwt.header=Authorization
http.auth.jwt.prefix=Bearer
```

#### HashiCorp Vault Integration
```properties
http.auth.type=vault
http.auth.vault.address=https://vault.example.com
http.auth.vault.token=vault-token
http.auth.vault.secret.path=secret/api-credentials
http.auth.vault.field.username=username
http.auth.vault.field.password=password
http.auth.vault.renewal.enabled=true
http.auth.vault.renewal.threshold=300
```

#### AWS IAM Authentication
```properties
http.auth.type=aws_iam
http.auth.aws.region=us-west-2
http.auth.aws.service=execute-api
http.auth.aws.access.key.id=AKIA...
http.auth.aws.secret.access.key=secret-key
http.auth.aws.session.token=session-token
http.auth.aws.assume.role.arn=arn:aws:iam::123456789012:role/MyRole
```

#### Azure Active Directory Authentication
```properties
http.auth.type=azure_ad
http.auth.azure.tenant.id=tenant-id
http.auth.azure.client.id=client-id
http.auth.azure.client.secret=client-secret
http.auth.azure.resource=https://api.example.com
http.auth.azure.token.refresh.enabled=true
```

### Advanced Features Configuration

#### Pagination Support
```properties
# Offset-based pagination
pagination.type=offset
pagination.offset.param=offset
pagination.limit.param=limit
pagination.limit.value=100
pagination.max.pages=1000

# Cursor-based pagination
pagination.type=cursor
pagination.cursor.param=cursor
pagination.cursor.field=next_cursor
pagination.page.size=50

# Link header pagination
pagination.type=link_header
pagination.link.header=Link
pagination.link.rel=next
```

#### Caching Configuration
```properties
# Enable intelligent caching
cache.enabled=true
cache.maintenance.interval.seconds=300
cache.statistics.enabled=true

# Response cache
cache.response.max.size=1000
cache.response.ttl.seconds=3600

# Schema cache
cache.schema.max.size=100
cache.schema.ttl.seconds=7200

# Auth cache
cache.auth.max.size=50
cache.auth.ttl.seconds=900
```

#### Rate Limiting Configuration
```properties
# Enable rate limiting
rate.limit.enabled=true
rate.limit.requests.per.second=10
rate.limit.algorithm=TOKEN_BUCKET
rate.limit.burst.capacity=20
rate.limit.scope=GLOBAL
```

#### SSL/TLS Configuration
```properties
# SSL settings
ssl.enabled=true
ssl.keystore.location=/path/to/keystore.jks
ssl.keystore.password=keystore-password
ssl.truststore.location=/path/to/truststore.jks
ssl.truststore.password=truststore-password
ssl.validation.level=STRICT
ssl.certificate.pinning.enabled=true
ssl.certificate.pins=sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

#### Data Transformation Configuration
```properties
# Enable transformation
transformation.enabled=true
transformation.template.enabled=true

# Field mapping
transformation.field.mappings=[
  {"source": "api_id", "target": "id", "type": "STRING"},
  {"source": "created_at", "target": "timestamp", "type": "TIMESTAMP"}
]

# Data enrichment
transformation.enrichment.rules=[
  {"field": "source", "value": "api", "type": "STATIC"},
  {"field": "processed_at", "expression": "${now}", "type": "EXPRESSION"}
]

# Filtering
transformation.filter.expressions=[
  {"field": "status", "operator": "EQUALS", "value": "active"},
  {"field": "score", "operator": "GREATER_THAN", "value": 50}
]
```

### Monitoring and JMX Configuration

```properties
# JMX monitoring
jmx.enabled=true
jmx.domain=kafka.connect.http
jmx.object.name=type=HttpSourceConnector

# Health check endpoints
health.check.enabled=true
health.check.port=8080
health.check.path=/health
```

## Examples

### Example 1: Simple REST API Integration
```properties
name=simple-api-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=1

http.source.url=https://api.example.com/users
http.source.method=GET
http.source.headers=Accept:application/json
http.source.poll.interval.ms=30000

kafka.topic=users-topic

# Basic authentication
http.auth.type=basic
http.auth.basic.username=api-user
http.auth.basic.password=api-password
```

### Example 2: OAuth2 with Pagination
```properties
name=oauth2-paginated-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=2

http.source.url=https://api.example.com/orders
http.source.method=GET
http.source.poll.interval.ms=60000

kafka.topic=orders-topic

# OAuth2 authentication
http.auth.type=oauth2
http.auth.oauth2.client.id=${env:OAUTH_CLIENT_ID}
http.auth.oauth2.client.secret=${env:OAUTH_CLIENT_SECRET}
http.auth.oauth2.token.url=https://api.example.com/oauth/token
http.auth.oauth2.scope=read:orders

# Cursor-based pagination
pagination.type=cursor
pagination.cursor.param=cursor
pagination.cursor.field=next_cursor
pagination.page.size=100

# Enable caching
cache.enabled=true
cache.response.ttl.seconds=1800
```

### Example 3: Enterprise Configuration with Vault
```properties
name=enterprise-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=4

http.source.url=https://api.enterprise.com/data
http.source.method=GET
http.source.poll.interval.ms=120000

kafka.topic=enterprise-data

# Vault authentication
http.auth.type=vault
http.auth.vault.address=https://vault.enterprise.com
http.auth.vault.token=${env:VAULT_TOKEN}
http.auth.vault.secret.path=secret/api-credentials
http.auth.vault.field.username=username
http.auth.vault.field.password=password

# SSL with certificate pinning
ssl.enabled=true
ssl.validation.level=STRICT
ssl.certificate.pinning.enabled=true
ssl.certificate.pins=sha256:ENTERPRISE_CERT_HASH

# Rate limiting
rate.limit.enabled=true
rate.limit.requests.per.second=5
rate.limit.algorithm=SLIDING_WINDOW

# Data transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "id", "target": "record_id", "type": "STRING"},
  {"source": "timestamp", "target": "event_time", "type": "TIMESTAMP"},
  {"source": "data.value", "target": "value", "type": "DOUBLE"}
]

# Monitoring
jmx.enabled=true
health.check.enabled=true
health.check.port=8080
```

### Example 4: AWS API Gateway Integration
```properties
name=aws-api-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=1

http.source.url=https://api-id.execute-api.us-west-2.amazonaws.com/prod/data
http.source.method=GET
http.source.poll.interval.ms=60000

kafka.topic=aws-data

# AWS IAM authentication
http.auth.type=aws_iam
http.auth.aws.region=us-west-2
http.auth.aws.service=execute-api
http.auth.aws.access.key.id=${env:AWS_ACCESS_KEY_ID}
http.auth.aws.secret.access.key=${env:AWS_SECRET_ACCESS_KEY}

# Pagination
pagination.type=offset
pagination.offset.param=nextToken
pagination.limit.param=maxResults
pagination.limit.value=50
```

## Performance Tuning

### Connection Pool Optimization
```properties
# HTTP client settings
http.client.connection.timeout.ms=30000
http.client.read.timeout.ms=60000
http.client.max.connections=20
http.client.max.connections.per.route=10
http.client.connection.pool.enabled=true
```

### Memory Optimization
```properties
# Memory settings
http.client.max.response.size.bytes=10485760  # 10MB
cache.response.max.size=500  # Reduce for memory constraints
transformation.batch.size=100
```

### Throughput Optimization
```properties
# Increase parallelism
tasks.max=4
http.source.poll.interval.ms=30000  # More frequent polling

# Optimize caching
cache.enabled=true
cache.response.ttl.seconds=3600
cache.schema.ttl.seconds=7200

# Rate limiting adjustment
rate.limit.requests.per.second=20
rate.limit.burst.capacity=50
```

## Troubleshooting

### Common Issues and Solutions

#### Authentication Failures
1. **OAuth2 Token Expired**
   - Enable automatic token refresh: `http.auth.oauth2.token.refresh.enabled=true`
   - Adjust refresh buffer: `http.auth.oauth2.token.refresh.buffer.seconds=300`

2. **Vault Connection Issues**
   - Verify Vault address and token
   - Check network connectivity
   - Enable vault renewal: `http.auth.vault.renewal.enabled=true`

#### Performance Issues
1. **High Memory Usage**
   - Reduce cache sizes
   - Lower `http.client.max.response.size.bytes`
   - Adjust `transformation.batch.size`

2. **Rate Limiting Errors**
   - Adjust rate limit settings
   - Implement exponential backoff
   - Check API provider limits

#### Data Processing Issues
1. **Schema Conflicts**
   - Enable schema caching: `cache.schema.enabled=true`
   - Use schema evolution compatibility
   - Validate field mappings

2. **Transformation Errors**
   - Check field mapping configuration
   - Validate template expressions
   - Review filter conditions

### Logging Configuration

Add to your `log4j.properties`:
```properties
# HTTP Source Connector logging
log4j.logger.io.confluent.connect.http=DEBUG, stdout
log4j.logger.io.confluent.connect.http.auth=INFO, stdout
log4j.logger.io.confluent.connect.http.cache=INFO, stdout
log4j.logger.io.confluent.connect.http.performance=DEBUG, stdout
```

### Health Check Monitoring

Monitor connector health using the built-in endpoints:
- `GET /health` - Overall health status
- `GET /health/live` - Liveness check
- `GET /health/ready` - Readiness check
- `GET /health/auth` - Authentication status
- `GET /health/apis` - API endpoint status
- `GET /health/cache` - Cache statistics
- `GET /health/rate-limit` - Rate limiting status

### JMX Metrics Monitoring

Key metrics to monitor:
- `MessagesProcessed` - Total messages processed
- `BytesProcessed` - Total bytes processed
- `ErrorCount` - Number of errors
- `AuthenticationFailures` - Authentication failures
- `CacheHitRatio` - Cache hit ratio
- `RateLimitViolations` - Rate limit violations
- `AverageResponseTime` - Average API response time

## Migration Guides

### Migrating from Confluent HTTP Source Connector

1. Update connector class:
   ```properties
   connector.class=io.confluent.connect.http.HttpSourceConnector
   ```

2. Update authentication configuration (if using OAuth2):
   ```properties
   # Old format
   http.auth.type=oauth2
   oauth.client.id=client-id
   oauth.client.secret=client-secret
   
   # New format
   http.auth.type=oauth2
   http.auth.oauth2.client.id=client-id
   http.auth.oauth2.client.secret=client-secret
   ```

3. Enable new features:
   ```properties
   # Add caching
   cache.enabled=true
   
   # Add monitoring
   jmx.enabled=true
   health.check.enabled=true
   
   # Add rate limiting
   rate.limit.enabled=true
   ```

### Best Practices

1. **Security**
   - Use environment variables for sensitive configuration
   - Enable SSL/TLS with certificate validation
   - Implement credential rotation
   - Use least-privilege authentication

2. **Performance**
   - Enable caching for frequently accessed data
   - Implement appropriate rate limiting
   - Use pagination for large datasets
   - Monitor JMX metrics

3. **Reliability**
   - Configure proper error handling
   - Implement circuit breaker patterns
   - Use health check endpoints
   - Set up monitoring and alerting

4. **Operational**
   - Use structured logging
   - Implement proper testing
   - Document configuration changes
   - Plan for scaling and maintenance

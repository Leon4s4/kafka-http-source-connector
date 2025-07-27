# Configuration Examples for Different Use Cases

This document provides comprehensive configuration examples for various real-world scenarios using the Kafka HTTP Source Connector.

## Table of Contents
1. [E-commerce API Integration](#e-commerce-api-integration)
2. [Financial Data Streaming](#financial-data-streaming)
3. [IoT Sensor Data Collection](#iot-sensor-data-collection)
4. [Social Media API Integration](#social-media-api-integration)
5. [Enterprise SaaS Integration](#enterprise-saas-integration)
6. [Government Open Data](#government-open-data)
7. [Real-time Analytics](#real-time-analytics)
8. [Microservices Communication](#microservices-communication)

## E-commerce API Integration

### Scenario: Shopify Order Streaming
Stream order data from Shopify API with OAuth2 authentication and real-time processing.

```properties
# Connector Configuration
name=shopify-orders-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=2

# HTTP Source Configuration
http.source.url=https://your-shop.myshopify.com/admin/api/2024-01/orders.json
http.source.method=GET
http.source.headers=Accept:application/json;User-Agent:KafkaConnector/1.0
http.source.poll.interval.ms=30000

# Kafka Configuration
kafka.topic=shopify-orders
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=false

# OAuth2 Authentication
http.auth.type=oauth2
http.auth.oauth2.client.id=${env:SHOPIFY_CLIENT_ID}
http.auth.oauth2.client.secret=${env:SHOPIFY_CLIENT_SECRET}
http.auth.oauth2.token.url=https://your-shop.myshopify.com/admin/oauth/access_token
http.auth.oauth2.scope=read_orders,read_products
http.auth.oauth2.grant.type=authorization_code
http.auth.oauth2.token.refresh.enabled=true
http.auth.oauth2.token.refresh.buffer.seconds=300

# Pagination (Shopify uses cursor-based)
pagination.type=cursor
pagination.cursor.param=page_info
pagination.cursor.field=page_info
pagination.page.size=50
pagination.max.pages=100

# Rate Limiting (Shopify API limits)
rate.limit.enabled=true
rate.limit.requests.per.second=2
rate.limit.algorithm=TOKEN_BUCKET
rate.limit.burst.capacity=5

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "id", "target": "order_id", "type": "LONG"},
  {"source": "created_at", "target": "order_date", "type": "TIMESTAMP"},
  {"source": "total_price", "target": "amount", "type": "DOUBLE"},
  {"source": "currency", "target": "currency_code", "type": "STRING"},
  {"source": "customer.email", "target": "customer_email", "type": "STRING"}
]

# Enrichment
transformation.enrichment.rules=[
  {"field": "source_system", "value": "shopify", "type": "STATIC"},
  {"field": "ingestion_timestamp", "expression": "${now}", "type": "EXPRESSION"},
  {"field": "partition_date", "expression": "${date:yyyy-MM-dd}", "type": "EXPRESSION"}
]

# Filtering (only confirmed orders)
transformation.filter.expressions=[
  {"field": "financial_status", "operator": "EQUALS", "value": "paid"},
  {"field": "fulfillment_status", "operator": "NOT_EQUALS", "value": "cancelled"}
]

# Caching
cache.enabled=true
cache.response.ttl.seconds=300
cache.auth.ttl.seconds=3300

# Monitoring
jmx.enabled=true
health.check.enabled=true
health.check.port=8081
```

## Financial Data Streaming

### Scenario: Real-time Stock Price Data
Stream stock price data from Alpha Vantage API with API key authentication.

```properties
# Connector Configuration
name=stock-prices-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=1

# HTTP Source Configuration
http.source.url=https://www.alphavantage.co/query
http.source.method=GET
http.source.headers=Accept:application/json
http.source.poll.interval.ms=60000

# Query Parameters Template
http.source.query.params=function=TIME_SERIES_INTRADAY&symbol=AAPL&interval=1min&apikey=${auth.api.key}

# Kafka Configuration
kafka.topic=stock-prices
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter

# API Key Authentication
http.auth.type=api_key
http.auth.api.key=${env:ALPHA_VANTAGE_API_KEY}
http.auth.api.key.header=X-RapidAPI-Key

# Rate Limiting (Alpha Vantage limits)
rate.limit.enabled=true
rate.limit.requests.per.second=5
rate.limit.algorithm=SLIDING_WINDOW
rate.limit.burst.capacity=10

# Data Transformation
transformation.enabled=true
transformation.template.enabled=true

# Transform time series data
transformation.field.mappings=[
  {"source": "Time Series (1min)", "target": "time_series", "type": "OBJECT"},
  {"source": "Meta Data.2. Symbol", "target": "symbol", "type": "STRING"},
  {"source": "Meta Data.3. Last Refreshed", "target": "last_updated", "type": "TIMESTAMP"}
]

# Enrichment with market indicators
transformation.enrichment.rules=[
  {"field": "market", "value": "NYSE", "type": "STATIC"},
  {"field": "data_provider", "value": "alphavantage", "type": "STATIC"},
  {"field": "collection_time", "expression": "${now}", "type": "EXPRESSION"}
]

# SSL Configuration
ssl.enabled=true
ssl.validation.level=STRICT

# Caching (short TTL for real-time data)
cache.enabled=true
cache.response.ttl.seconds=30

# Monitoring
jmx.enabled=true
```

## IoT Sensor Data Collection

### Scenario: Environmental Sensor Network
Collect data from IoT sensors via REST API with device authentication.

```properties
# Connector Configuration
name=iot-sensors-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=4

# HTTP Source Configuration
http.source.url=https://iot-gateway.example.com/api/v1/sensors/readings
http.source.method=GET
http.source.headers=Accept:application/json;X-Device-Type:environmental
http.source.poll.interval.ms=10000

# Kafka Configuration
kafka.topic=iot-sensor-data

# JWT Authentication (device token)
http.auth.type=jwt
http.auth.jwt.token=${env:IOT_DEVICE_TOKEN}
http.auth.jwt.header=Authorization
http.auth.jwt.prefix=Bearer

# Pagination for multiple sensors
pagination.type=offset
pagination.offset.param=offset
pagination.limit.param=limit
pagination.limit.value=200
pagination.max.pages=50

# High frequency rate limit
rate.limit.enabled=true
rate.limit.requests.per.second=10
rate.limit.algorithm=TOKEN_BUCKET

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "sensor_id", "target": "device_id", "type": "STRING"},
  {"source": "timestamp", "target": "reading_time", "type": "TIMESTAMP"},
  {"source": "temperature", "target": "temp_celsius", "type": "DOUBLE"},
  {"source": "humidity", "target": "humidity_percent", "type": "DOUBLE"},
  {"source": "pressure", "target": "pressure_hpa", "type": "DOUBLE"}
]

# Data validation and filtering
transformation.filter.expressions=[
  {"field": "temp_celsius", "operator": "BETWEEN", "min": -50, "max": 80},
  {"field": "humidity_percent", "operator": "BETWEEN", "min": 0, "max": 100},
  {"field": "sensor_status", "operator": "EQUALS", "value": "active"}
]

# Enrichment with location data
transformation.enrichment.rules=[
  {"field": "location_type", "value": "outdoor", "type": "STATIC"},
  {"field": "data_quality", "expression": "${if(temp_celsius > -20 && temp_celsius < 60, 'good', 'questionable')}", "type": "EXPRESSION"}
]

# Minimal caching for real-time data
cache.enabled=true
cache.response.ttl.seconds=5

# Circuit breaker for unreliable sensors
circuit.breaker.enabled=true
circuit.breaker.failure.threshold=5
circuit.breaker.recovery.timeout.ms=30000
```

## Social Media API Integration

### Scenario: Twitter API v2 Integration
Stream tweets using Twitter API v2 with OAuth2 Bearer token.

```properties
# Connector Configuration
name=twitter-stream-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=1

# HTTP Source Configuration
http.source.url=https://api.twitter.com/2/tweets/search/recent
http.source.method=GET
http.source.poll.interval.ms=60000

# Query parameters for tweet search
http.source.query.params=query=kafka OR "apache kafka"&max_results=100&tweet.fields=created_at,author_id,public_metrics

# Kafka Configuration
kafka.topic=twitter-mentions

# OAuth2 Bearer Token Authentication
http.auth.type=oauth2
http.auth.oauth2.token.type=bearer
http.auth.oauth2.bearer.token=${env:TWITTER_BEARER_TOKEN}

# Twitter API rate limits
rate.limit.enabled=true
rate.limit.requests.per.second=1
rate.limit.algorithm=SLIDING_WINDOW
rate.limit.burst.capacity=2

# Pagination using Twitter's next_token
pagination.type=cursor
pagination.cursor.param=next_token
pagination.cursor.field=meta.next_token
pagination.max.pages=10

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "data", "target": "tweets", "type": "ARRAY"},
  {"source": "data.id", "target": "tweet_id", "type": "STRING"},
  {"source": "data.text", "target": "content", "type": "STRING"},
  {"source": "data.created_at", "target": "posted_at", "type": "TIMESTAMP"},
  {"source": "data.author_id", "target": "user_id", "type": "STRING"},
  {"source": "data.public_metrics.retweet_count", "target": "retweets", "type": "INTEGER"},
  {"source": "data.public_metrics.like_count", "target": "likes", "type": "INTEGER"}
]

# Content filtering
transformation.filter.expressions=[
  {"field": "content", "operator": "NOT_CONTAINS", "value": "spam"},
  {"field": "retweets", "operator": "GREATER_THAN", "value": 0}
]

# Enrichment
transformation.enrichment.rules=[
  {"field": "platform", "value": "twitter", "type": "STATIC"},
  {"field": "sentiment_analyzed", "value": false, "type": "STATIC"},
  {"field": "processed_at", "expression": "${now}", "type": "EXPRESSION"}
]

# SSL Configuration
ssl.enabled=true
ssl.validation.level=STRICT

# Caching
cache.enabled=true
cache.response.ttl.seconds=300
```

## Enterprise SaaS Integration

### Scenario: Salesforce CRM Integration
Integrate with Salesforce REST API using OAuth2 with automatic token refresh.

```properties
# Connector Configuration
name=salesforce-leads-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=2

# HTTP Source Configuration
http.source.url=https://your-instance.salesforce.com/services/data/v58.0/query
http.source.method=GET
http.source.headers=Accept:application/json
http.source.poll.interval.ms=300000

# SOQL Query for leads
http.source.query.params=q=SELECT Id,FirstName,LastName,Email,Company,Status,CreatedDate FROM Lead WHERE CreatedDate >= YESTERDAY

# Kafka Configuration
kafka.topic=salesforce-leads

# OAuth2 Configuration
http.auth.type=oauth2
http.auth.oauth2.client.id=${env:SALESFORCE_CLIENT_ID}
http.auth.oauth2.client.secret=${env:SALESFORCE_CLIENT_SECRET}
http.auth.oauth2.token.url=https://login.salesforce.com/services/oauth2/token
http.auth.oauth2.grant.type=client_credentials
http.auth.oauth2.token.refresh.enabled=true
http.auth.oauth2.token.refresh.buffer.seconds=600

# Salesforce pagination
pagination.type=link_header
pagination.link.header=Link
pagination.link.rel=next
pagination.max.pages=100

# Conservative rate limiting
rate.limit.enabled=true
rate.limit.requests.per.second=5
rate.limit.algorithm=TOKEN_BUCKET

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "records", "target": "leads", "type": "ARRAY"},
  {"source": "records.Id", "target": "lead_id", "type": "STRING"},
  {"source": "records.FirstName", "target": "first_name", "type": "STRING"},
  {"source": "records.LastName", "target": "last_name", "type": "STRING"},
  {"source": "records.Email", "target": "email", "type": "STRING"},
  {"source": "records.Company", "target": "company_name", "type": "STRING"},
  {"source": "records.Status", "target": "lead_status", "type": "STRING"},
  {"source": "records.CreatedDate", "target": "created_date", "type": "TIMESTAMP"}
]

# Data quality filtering
transformation.filter.expressions=[
  {"field": "email", "operator": "IS_NOT_NULL"},
  {"field": "lead_status", "operator": "IN", "values": ["New", "Working", "Qualified"]}
]

# Enrichment
transformation.enrichment.rules=[
  {"field": "source_system", "value": "salesforce", "type": "STATIC"},
  {"field": "sync_timestamp", "expression": "${now}", "type": "EXPRESSION"},
  {"field": "data_classification", "value": "customer_data", "type": "STATIC"}
]

# SSL with enterprise security
ssl.enabled=true
ssl.validation.level=STRICT
ssl.certificate.pinning.enabled=true

# Schema caching for performance
cache.enabled=true
cache.schema.ttl.seconds=7200
cache.response.ttl.seconds=1800

# Enterprise monitoring
jmx.enabled=true
health.check.enabled=true
health.check.port=8082
```

## Government Open Data

### Scenario: Weather Data Integration
Stream weather data from NOAA (National Weather Service) API.

```properties
# Connector Configuration
name=noaa-weather-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=1

# HTTP Source Configuration
http.source.url=https://api.weather.gov/stations/KNYC/observations
http.source.method=GET
http.source.headers=Accept:application/geo+json;User-Agent:KafkaConnect/1.0
http.source.poll.interval.ms=600000

# Kafka Configuration
kafka.topic=weather-observations

# No authentication required for NOAA API
http.auth.type=none

# Pagination for historical data
pagination.type=offset
pagination.offset.param=start
pagination.limit.param=limit
pagination.limit.value=50

# Respectful rate limiting for public API
rate.limit.enabled=true
rate.limit.requests.per.second=1
rate.limit.algorithm=SLIDING_WINDOW

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "features", "target": "observations", "type": "ARRAY"},
  {"source": "features.properties.timestamp", "target": "observation_time", "type": "TIMESTAMP"},
  {"source": "features.properties.temperature.value", "target": "temperature_celsius", "type": "DOUBLE"},
  {"source": "features.properties.windSpeed.value", "target": "wind_speed_mps", "type": "DOUBLE"},
  {"source": "features.properties.windDirection.value", "target": "wind_direction_degrees", "type": "INTEGER"},
  {"source": "features.properties.barometricPressure.value", "target": "pressure_pa", "type": "DOUBLE"},
  {"source": "features.properties.relativeHumidity.value", "target": "humidity_percent", "type": "DOUBLE"}
]

# Data validation
transformation.filter.expressions=[
  {"field": "temperature_celsius", "operator": "BETWEEN", "min": -50, "max": 60},
  {"field": "humidity_percent", "operator": "BETWEEN", "min": 0, "max": 100}
]

# Enrichment
transformation.enrichment.rules=[
  {"field": "station_id", "value": "KNYC", "type": "STATIC"},
  {"field": "location", "value": "New York City", "type": "STATIC"},
  {"field": "data_source", "value": "NOAA", "type": "STATIC"},
  {"field": "temperature_fahrenheit", "expression": "${math:celsius_to_fahrenheit(temperature_celsius)}", "type": "EXPRESSION"}
]

# Long caching for relatively stable data
cache.enabled=true
cache.response.ttl.seconds=1800

# Circuit breaker for government API reliability
circuit.breaker.enabled=true
circuit.breaker.failure.threshold=3
circuit.breaker.recovery.timeout.ms=60000
```

## Real-time Analytics

### Scenario: Application Performance Monitoring
Stream APM data from New Relic API for real-time analytics.

```properties
# Connector Configuration
name=newrelic-apm-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=3

# HTTP Source Configuration
http.source.url=https://api.newrelic.com/v2/applications/123456/metrics/data.json
http.source.method=GET
http.source.poll.interval.ms=60000

# Query parameters for metrics
http.source.query.params=names[]=Apdex&names[]=HttpDispatcher&values[]=average_response_time&values[]=throughput&from=${timestamp:now-5m}&to=${timestamp:now}

# Kafka Configuration
kafka.topic=apm-metrics

# API Key Authentication
http.auth.type=api_key
http.auth.api.key=${env:NEWRELIC_API_KEY}
http.auth.api.key.header=X-Api-Key

# New Relic rate limits
rate.limit.enabled=true
rate.limit.requests.per.second=3
rate.limit.algorithm=TOKEN_BUCKET

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "metric_data.metrics", "target": "metrics", "type": "ARRAY"},
  {"source": "metric_data.metrics.name", "target": "metric_name", "type": "STRING"},
  {"source": "metric_data.metrics.timeslices", "target": "timeslices", "type": "ARRAY"},
  {"source": "metric_data.metrics.timeslices.from", "target": "from_time", "type": "TIMESTAMP"},
  {"source": "metric_data.metrics.timeslices.to", "target": "to_time", "type": "TIMESTAMP"},
  {"source": "metric_data.metrics.timeslices.values", "target": "values", "type": "OBJECT"}
]

# Performance alert filtering
transformation.filter.expressions=[
  {"field": "values.average_response_time", "operator": "GREATER_THAN", "value": 0},
  {"field": "values.throughput", "operator": "GREATER_THAN", "value": 0}
]

# Enrichment with alerting thresholds
transformation.enrichment.rules=[
  {"field": "application_id", "value": "123456", "type": "STATIC"},
  {"field": "environment", "value": "production", "type": "STATIC"},
  {"field": "alert_threshold_exceeded", "expression": "${if(values.average_response_time > 2.0, true, false)}", "type": "EXPRESSION"},
  {"field": "collection_timestamp", "expression": "${now}", "type": "EXPRESSION"}
]

# Minimal caching for real-time metrics
cache.enabled=true
cache.response.ttl.seconds=30

# SSL security
ssl.enabled=true
ssl.validation.level=STRICT

# Monitoring
jmx.enabled=true
```

## Microservices Communication

### Scenario: Service Health Monitoring
Monitor microservice health endpoints across multiple services.

```properties
# Connector Configuration
name=microservices-health-connector
connector.class=io.confluent.connect.http.HttpSourceConnector
tasks.max=5

# HTTP Source Configuration (using API chaining for multiple services)
http.source.url=https://service-discovery.internal.com/api/v1/services
http.source.method=GET
http.source.headers=Accept:application/json;X-Internal-Request:true
http.source.poll.interval.ms=30000

# Kafka Configuration
kafka.topic=service-health-status

# Internal service authentication
http.auth.type=jwt
http.auth.jwt.token=${env:INTERNAL_SERVICE_TOKEN}
http.auth.jwt.header=Authorization
http.auth.jwt.prefix=Bearer

# API Chaining for health endpoints
api.chaining.enabled=true
api.chaining.extract.field=services
api.chaining.url.template=https://{service.host}:{service.port}/health
api.chaining.method=GET
api.chaining.max.concurrent=10

# High-frequency monitoring
rate.limit.enabled=true
rate.limit.requests.per.second=20
rate.limit.algorithm=TOKEN_BUCKET

# Data Transformation
transformation.enabled=true
transformation.field.mappings=[
  {"source": "service_name", "target": "service", "type": "STRING"},
  {"source": "status", "target": "health_status", "type": "STRING"},
  {"source": "response_time_ms", "target": "response_time", "type": "LONG"},
  {"source": "version", "target": "service_version", "type": "STRING"},
  {"source": "dependencies", "target": "dependency_status", "type": "OBJECT"}
]

# Alert on unhealthy services
transformation.filter.expressions=[
  {"field": "health_status", "operator": "IN", "values": ["healthy", "degraded", "unhealthy"]}
]

# Enrichment
transformation.enrichment.rules=[
  {"field": "environment", "value": "production", "type": "STATIC"},
  {"field": "monitoring_source", "value": "kafka-connector", "type": "STATIC"},
  {"field": "check_timestamp", "expression": "${now}", "type": "EXPRESSION"},
  {"field": "alert_required", "expression": "${if(health_status == 'unhealthy', true, false)}", "type": "EXPRESSION"}
]

# Minimal caching for health data
cache.enabled=true
cache.response.ttl.seconds=10

# Circuit breaker for service dependencies
circuit.breaker.enabled=true
circuit.breaker.failure.threshold=3
circuit.breaker.recovery.timeout.ms=30000

# Monitoring
jmx.enabled=true
health.check.enabled=true
health.check.port=8083
```

## Configuration Templates

### Template: High-Volume Data Streaming
```properties
# Optimized for high-volume, low-latency streaming
tasks.max=8
http.source.poll.interval.ms=5000

rate.limit.enabled=true
rate.limit.requests.per.second=50
rate.limit.algorithm=TOKEN_BUCKET
rate.limit.burst.capacity=100

cache.enabled=true
cache.response.ttl.seconds=30
cache.response.max.size=2000

transformation.batch.size=500
```

### Template: Enterprise Security
```properties
# Maximum security configuration
ssl.enabled=true
ssl.validation.level=STRICT
ssl.certificate.pinning.enabled=true
ssl.certificate.pins=sha256:YOUR_CERT_HASH

http.auth.type=vault
http.auth.vault.address=${env:VAULT_ADDR}
http.auth.vault.token=${env:VAULT_TOKEN}
http.auth.vault.renewal.enabled=true

circuit.breaker.enabled=true
jmx.enabled=true
health.check.enabled=true
```

### Template: Development Environment
```properties
# Development-friendly configuration
ssl.validation.level=RELAXED
cache.enabled=false
rate.limit.enabled=false

jmx.enabled=true
health.check.enabled=true
health.check.port=8080

# Debug logging
log.level=DEBUG
```

These examples demonstrate the connector's flexibility and enterprise-grade capabilities across various industries and use cases. Each configuration can be adapted to specific requirements while maintaining performance, security, and reliability standards.

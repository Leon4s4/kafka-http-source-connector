# Performance Tuning Guide

This guide provides comprehensive performance tuning strategies for the Kafka HTTP Source Connector to achieve optimal throughput, minimize latency, and efficiently utilize system resources.

## Table of Contents
1. [Performance Overview](#performance-overview)
2. [Throughput Optimization](#throughput-optimization)
3. [Latency Reduction](#latency-reduction)
4. [Memory Optimization](#memory-optimization)
5. [Network Optimization](#network-optimization)
6. [Caching Strategies](#caching-strategies)
7. [Monitoring and Metrics](#monitoring-and-metrics)
8. [Scaling Strategies](#scaling-strategies)
9. [Best Practices](#best-practices)

## Performance Overview

### Key Performance Metrics
- **Throughput**: Records per second processed
- **Latency**: Time from API call to Kafka record production
- **Resource Utilization**: CPU, memory, and network usage
- **Error Rate**: Failed requests and processing errors
- **Cache Hit Ratio**: Effectiveness of caching mechanisms

### Performance Factors
1. **API Characteristics**: Response time, payload size, rate limits
2. **Network Conditions**: Bandwidth, latency, reliability
3. **Connector Configuration**: Polling intervals, parallelism, caching
4. **System Resources**: Available CPU, memory, and I/O capacity
5. **Kafka Cluster**: Broker performance, topic configuration

## Throughput Optimization

### 1. Parallel Processing

#### Increase Task Parallelism
```properties
# Optimal task count (start with 2x CPU cores)
tasks.max=8

# Ensure sufficient topic partitions for parallel processing
# Kafka topic should have >= tasks.max partitions
```

#### Configuration for High Throughput
```properties
# High-throughput connector configuration
name=high-throughput-connector
connector.class=io.confluent.connect.http.HttpSourceConnector

# Parallel execution
tasks.max=8

# Aggressive polling
http.source.poll.interval.ms=10000

# Batch processing
transformation.batch.size=500
pagination.limit.value=200
pagination.max.pages=50

# Connection pooling
http.client.connection.pool.enabled=true
http.client.max.connections=30
http.client.max.connections.per.route=15

# Reduce overhead
transformation.enabled=false  # If not needed
cache.enabled=false  # For real-time data
```

### 2. Optimized Pagination

#### Large Page Sizes
```properties
# Maximize data per request
pagination.limit.value=1000  # API dependent
pagination.max.pages=100

# Efficient pagination strategy
pagination.type=cursor  # Usually faster than offset
pagination.cursor.field=next_cursor
```

#### Concurrent Page Processing
```properties
# Enable concurrent pagination
pagination.concurrent.enabled=true
pagination.concurrent.max.threads=4
pagination.concurrent.queue.size=50
```

### 3. Efficient Data Processing

#### Minimal Transformation
```properties
# Only essential transformations
transformation.enabled=true
transformation.field.mappings=[
  {"source": "id", "target": "record_id", "type": "STRING"}
]

# Skip expensive operations
transformation.enrichment.enabled=false
transformation.validation.enabled=false
```

#### Bulk Operations
```properties
# Process data in batches
transformation.batch.size=1000
producer.batch.size=65536
producer.linger.ms=5
```

## Latency Reduction

### 1. Minimize Network Latency

#### Connection Optimization
```properties
# Persistent connections
http.client.connection.pool.enabled=true
http.client.connection.keep.alive.enabled=true
http.client.connection.keep.alive.duration.ms=300000

# Reduce connection establishment overhead
http.client.connection.timeout.ms=5000
http.client.socket.timeout.ms=10000
```

#### HTTP/2 Support (if available)
```properties
# Enable HTTP/2 for better performance
http.client.http2.enabled=true
http.client.http2.max.concurrent.streams=100
```

### 2. Aggressive Caching

#### Response Caching
```properties
# Cache frequently accessed data
cache.enabled=true
cache.response.max.size=5000
cache.response.ttl.seconds=300

# Intelligent cache warming
cache.warming.enabled=true
cache.warming.strategies=PREDICTIVE,LRU
```

#### Schema Caching
```properties
# Long-term schema caching
cache.schema.enabled=true
cache.schema.max.size=500
cache.schema.ttl.seconds=14400  # 4 hours
```

### 3. Reduce Processing Overhead

#### Minimal Logging
```properties
# Reduce logging overhead in production
log.level=WARN
transformation.debug.enabled=false
performance.metrics.detailed=false
```

#### Optimized Serialization
```properties
# Use efficient serializers
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=false
```

## Memory Optimization

### 1. JVM Tuning

#### Heap Configuration
```bash
# JVM settings for Connect worker
export KAFKA_HEAP_OPTS="-Xms4g -Xmx8g"
export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

#### Garbage Collection Optimization
```bash
# G1GC for low-latency applications
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m
-XX:+UnlockExperimentalVMOptions
-XX:+UseCGroupMemoryLimitForHeap
```

### 2. Connector Memory Settings

#### Response Size Limits
```properties
# Prevent memory exhaustion
http.client.max.response.size.bytes=10485760  # 10MB
transformation.max.record.size.bytes=1048576  # 1MB
```

#### Cache Memory Management
```properties
# Memory-conscious caching
cache.max.memory.bytes=536870912  # 512MB
cache.eviction.policy=LRU
cache.cleanup.interval.seconds=300
```

#### Buffer Management
```properties
# Optimize buffer sizes
http.client.buffer.size.bytes=65536
producer.buffer.memory=134217728  # 128MB
```

### 3. Memory Leak Prevention

#### Connection Cleanup
```properties
# Prevent connection leaks
http.client.connection.cleanup.enabled=true
http.client.connection.cleanup.interval.ms=300000
http.client.connection.max.idle.time.ms=600000
```

#### Cache Eviction
```properties
# Aggressive cache eviction
cache.eviction.enabled=true
cache.eviction.threshold=0.8
cache.eviction.batch.size=100
```

## Network Optimization

### 1. Connection Pooling

#### Optimal Pool Configuration
```properties
# Connection pool tuning
http.client.connection.pool.enabled=true
http.client.max.connections=50
http.client.max.connections.per.route=20
http.client.connection.pool.validate.after.inactivity.ms=30000
```

#### Keep-Alive Configuration
```properties
# Maximize connection reuse
http.client.connection.keep.alive.enabled=true
http.client.connection.keep.alive.duration.ms=300000
http.client.connection.keep.alive.max.requests=1000
```

### 2. Compression

#### Request/Response Compression
```properties
# Enable compression when supported
http.client.compression.enabled=true
http.client.compression.types=gzip,deflate
http.source.headers=Accept-Encoding:gzip,deflate
```

### 3. Network Timeout Optimization

#### Balanced Timeouts
```properties
# Production-optimized timeouts
http.client.connection.timeout.ms=10000
http.client.socket.timeout.ms=30000
http.client.request.timeout.ms=60000

# DNS caching
http.client.dns.cache.ttl.seconds=300
```

## Caching Strategies

### 1. Multi-Level Caching

#### Response Caching Strategy
```properties
# Tiered cache configuration
cache.enabled=true

# L1 Cache: Hot data (short TTL, high hit rate)
cache.l1.max.size=1000
cache.l1.ttl.seconds=300
cache.l1.policy=LRU

# L2 Cache: Warm data (medium TTL, medium hit rate)
cache.l2.max.size=5000
cache.l2.ttl.seconds=3600
cache.l2.policy=LFU

# L3 Cache: Cold data (long TTL, low hit rate)
cache.l3.max.size=10000
cache.l3.ttl.seconds=14400
cache.l3.policy=FIFO
```

### 2. Predictive Caching

#### Cache Warming
```properties
# Preload frequently accessed data
cache.warming.enabled=true
cache.warming.schedule=0 */5 * * * *  # Every 5 minutes
cache.warming.patterns=[
  "users/*",
  "products/featured",
  "config/settings"
]
```

### 3. Cache Optimization

#### Cache Hit Rate Optimization
```properties
# Monitor and optimize cache performance
cache.statistics.enabled=true
cache.statistics.window.minutes=60
cache.hit.ratio.threshold=0.8

# Auto-tune cache sizes based on hit rates
cache.auto.tuning.enabled=true
cache.auto.tuning.interval.minutes=30
```

## Monitoring and Metrics

### 1. JMX Metrics Setup

#### Performance Metrics
```properties
# Enable comprehensive monitoring
jmx.enabled=true
jmx.metrics.detailed=true

# Key performance metrics to monitor:
# - MessagesPerSecond
# - AverageResponseTime
# - CacheHitRatio
# - ConnectionPoolUtilization
# - ErrorRate
```

#### Custom Metrics Collection
```java
// Example JMX monitoring script
MBeanServer server = ManagementFactory.getPlatformMBeanServer();
ObjectName objectName = new ObjectName("kafka.connect.http:type=HttpSourceConnector");

// Throughput monitoring
Long messagesPerSecond = (Long) server.getAttribute(objectName, "MessagesPerSecond");
Double averageResponseTime = (Double) server.getAttribute(objectName, "AverageResponseTime");
Double cacheHitRatio = (Double) server.getAttribute(objectName, "CacheHitRatio");
```

### 2. Health Check Optimization

#### Performance-Focused Health Checks
```properties
# Lightweight health checks
health.check.enabled=true
health.check.response.time.threshold.ms=5000
health.check.cache.hit.ratio.threshold=0.7
health.check.error.rate.threshold=0.05
```

### 3. Performance Alerts

#### Threshold-Based Alerting
```bash
#!/bin/bash
# performance-monitor.sh

THROUGHPUT_THRESHOLD=100
LATENCY_THRESHOLD=2000
CACHE_HIT_RATIO_THRESHOLD=0.8

# Check throughput
throughput=$(curl -s http://localhost:8080/health/metrics | jq '.throughput.messagesPerSecond')
if (( $(echo "$throughput < $THROUGHPUT_THRESHOLD" | bc -l) )); then
    echo "ALERT: Low throughput: $throughput msg/sec"
fi

# Check latency
latency=$(curl -s http://localhost:8080/health/metrics | jq '.latency.averageMs')
if (( $(echo "$latency > $LATENCY_THRESHOLD" | bc -l) )); then
    echo "ALERT: High latency: $latency ms"
fi

# Check cache hit ratio
hit_ratio=$(curl -s http://localhost:8080/health/metrics | jq '.cache.hitRatio')
if (( $(echo "$hit_ratio < $CACHE_HIT_RATIO_THRESHOLD" | bc -l) )); then
    echo "ALERT: Low cache hit ratio: $hit_ratio"
fi
```

## Scaling Strategies

### 1. Horizontal Scaling

#### Multi-Instance Deployment
```properties
# Instance 1: Handle endpoints A-M
name=http-connector-instance-1
http.source.url.pattern=https://api.example.com/[a-m]*

# Instance 2: Handle endpoints N-Z
name=http-connector-instance-2
http.source.url.pattern=https://api.example.com/[n-z]*
```

#### Load Balancing Configuration
```properties
# API endpoint load balancing
http.source.endpoints=[
  "https://api1.example.com",
  "https://api2.example.com",
  "https://api3.example.com"
]
http.load.balancing.strategy=ROUND_ROBIN
http.load.balancing.health.check.enabled=true
```

### 2. Vertical Scaling

#### Resource Allocation
```bash
# High-performance system configuration
# CPU: 16+ cores
# Memory: 32GB+ RAM
# Network: 10Gbps+
# Storage: NVMe SSD

# JVM settings for large systems
export KAFKA_HEAP_OPTS="-Xms16g -Xmx24g"
export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"
```

### 3. Auto-Scaling

#### Dynamic Task Scaling
```properties
# Auto-scaling configuration
auto.scaling.enabled=true
auto.scaling.min.tasks=2
auto.scaling.max.tasks=16
auto.scaling.scale.up.threshold.throughput=1000
auto.scaling.scale.down.threshold.throughput=100
auto.scaling.evaluation.interval.minutes=5
```

## Best Practices

### 1. Configuration Best Practices

#### Performance-Optimized Template
```properties
# Production high-performance template
name=high-performance-connector
connector.class=io.confluent.connect.http.HttpSourceConnector

# Parallelism
tasks.max=8

# Polling optimization
http.source.poll.interval.ms=15000

# Network optimization
http.client.connection.pool.enabled=true
http.client.max.connections=40
http.client.max.connections.per.route=20
http.client.connection.timeout.ms=10000
http.client.socket.timeout.ms=30000

# Caching optimization
cache.enabled=true
cache.response.max.size=3000
cache.response.ttl.seconds=600
cache.schema.max.size=200
cache.schema.ttl.seconds=7200

# Rate limiting (conservative)
rate.limit.enabled=true
rate.limit.requests.per.second=20
rate.limit.algorithm=TOKEN_BUCKET
rate.limit.burst.capacity=50

# Batch processing
transformation.batch.size=500
producer.batch.size=65536
producer.linger.ms=5

# Monitoring
jmx.enabled=true
health.check.enabled=true
```

### 2. Monitoring Best Practices

#### Key Metrics Dashboard
```yaml
# Grafana dashboard configuration
dashboard:
  metrics:
    - throughput: "Messages per second"
    - latency: "Average response time (ms)"
    - cache_hit_ratio: "Cache hit ratio (%)"
    - error_rate: "Error rate (%)"
    - connection_pool_utilization: "Connection pool usage (%)"
    - memory_utilization: "JVM heap usage (%)"
    - network_utilization: "Network I/O (MB/s)"
  
  alerts:
    - throughput_low: "< 100 msg/sec for 5 minutes"
    - latency_high: "> 2000ms for 3 minutes"
    - cache_hit_low: "< 80% for 10 minutes"
    - error_rate_high: "> 5% for 2 minutes"
```

### 3. Capacity Planning

#### Sizing Guidelines
```
# Connector sizing formula
Total Throughput = (Records per API call) × (API calls per second) × (Number of tasks)

# Example calculation:
# - API returns 100 records per call
# - Can make 10 calls per second (rate limited)
# - Running 4 tasks
# Total = 100 × 10 × 4 = 4,000 records/second

# Memory sizing:
# Base memory: 2GB
# Cache memory: (Cache size × Average record size) × 1.5
# Transformation memory: (Batch size × Average record size) × 2
# Total memory = Base + Cache + Transformation + 25% overhead
```

### 4. Performance Testing

#### Load Testing Script
```bash
#!/bin/bash
# load-test.sh - Connector performance testing

CONNECTOR_NAME="performance-test-connector"
TEST_DURATION=300  # 5 minutes

echo "Starting performance test for $CONNECTOR_NAME"

# Start monitoring
monitor_start_time=$(date +%s)

# Collect baseline metrics
echo "Collecting baseline metrics..."
curl -s http://localhost:8080/health/metrics > baseline-metrics.json

# Wait for test duration
echo "Running test for $TEST_DURATION seconds..."
sleep $TEST_DURATION

# Collect final metrics
echo "Collecting final metrics..."
curl -s http://localhost:8080/health/metrics > final-metrics.json

# Calculate performance metrics
monitor_end_time=$(date +%s)
test_duration=$((monitor_end_time - monitor_start_time))

baseline_messages=$(jq '.totalMessages' baseline-metrics.json)
final_messages=$(jq '.totalMessages' final-metrics.json)
total_processed=$((final_messages - baseline_messages))
throughput=$((total_processed / test_duration))

echo "Performance Test Results:"
echo "========================"
echo "Test Duration: $test_duration seconds"
echo "Messages Processed: $total_processed"
echo "Average Throughput: $throughput messages/second"
echo "Average Latency: $(jq '.averageLatency' final-metrics.json) ms"
echo "Cache Hit Ratio: $(jq '.cacheHitRatio' final-metrics.json)"
echo "Error Rate: $(jq '.errorRate' final-metrics.json)%"
```

### 5. Optimization Checklist

#### Pre-Production Checklist
- [ ] Task parallelism optimized for workload
- [ ] Connection pooling enabled and tuned
- [ ] Caching strategy implemented and tested
- [ ] Rate limiting configured appropriately
- [ ] JVM heap size and GC tuned
- [ ] Network timeouts optimized
- [ ] Monitoring and alerting configured
- [ ] Performance baselines established
- [ ] Load testing completed
- [ ] Resource limits and auto-scaling configured

#### Ongoing Optimization
- [ ] Monitor cache hit ratios weekly
- [ ] Review and adjust rate limits monthly
- [ ] Analyze performance trends quarterly
- [ ] Update capacity planning annually
- [ ] Review and optimize transformation logic as needed
- [ ] Monitor API provider rate limit changes
- [ ] Test performance impact of configuration changes

This performance tuning guide provides a comprehensive approach to optimizing the Kafka HTTP Source Connector for various performance requirements. Regular monitoring and iterative tuning based on actual workload patterns will help maintain optimal performance over time.

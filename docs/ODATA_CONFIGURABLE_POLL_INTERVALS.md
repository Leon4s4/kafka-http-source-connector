# OData Configurable Poll Intervals

This feature allows you to configure different polling intervals for OData APIs based on the type of pagination link being processed:

- **nextLink polling**: Fast polling for processing pagination (when `@odata.nextLink` is present)
- **deltaLink polling**: Slower polling for incremental updates (when `@odata.deltaLink` is present)
- **Standard polling**: Fallback polling interval for initial requests or unknown states

## Why This Matters

OData APIs typically use two types of pagination:

1. **nextLink (@odata.nextLink)**: Used for paging through large result sets
   - Should be polled quickly to process all pages efficiently
   - Example: Processing 10,000 records across 100 pages

2. **deltaLink (@odata.deltaLink)**: Used for incremental updates since last sync
   - Can be polled less frequently since it's for change detection
   - Example: Checking for new/updated records every 10 minutes

## Configuration Properties

### Required Configuration
- `api1.http.offset.mode`: Must be set to `ODATA_PAGINATION`
- `api1.request.interval.ms`: Standard polling interval (fallback)

### Optional OData Poll Interval Configuration
- `api1.odata.nextlink.poll.interval.ms`: Polling interval for nextLink processing (defaults to standard interval)
- `api1.odata.deltalink.poll.interval.ms`: Polling interval for deltaLink processing (defaults to standard interval)

## Example Configuration

```json
{
  "name": "dynamic-polling-example",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    "api1.http.offset.mode": "ODATA_PAGINATION",
    
    "api1.request.interval.ms": "300000",               // 5 minutes (standard)
    "api1.odata.nextlink.poll.interval.ms": "2000",    // 2 seconds (fast pagination)
    "api1.odata.deltalink.poll.interval.ms": "600000", // 10 minutes (slow incremental)
    
    "api1.odata.nextlink.field": "@odata.nextLink",
    "api1.odata.deltalink.field": "@odata.deltaLink",
    "api1.odata.token.mode": "FULL_URL"
  }
}
```

## How It Works

The connector automatically detects the current pagination state and applies the appropriate polling interval:

1. **Initial State**: Uses standard `request.interval.ms`
2. **NextLink Processing**: Uses `odata.nextlink.poll.interval.ms` when `@odata.nextLink` is being processed
3. **DeltaLink Processing**: Uses `odata.deltalink.poll.interval.ms` when `@odata.deltaLink` is being processed
4. **Fallback**: Uses standard `request.interval.ms` if OData-specific intervals are not configured

## Typical Use Cases

### High-Throughput Initial Sync
```json
{
  "api1.request.interval.ms": "60000",     // 1 minute standard
  "api1.odata.nextlink.poll.interval.ms": "1000",  // 1 second for fast pagination
  "api1.odata.deltalink.poll.interval.ms": "300000" // 5 minutes for incremental
}
```

### Low-Latency Change Detection
```json
{
  "api1.request.interval.ms": "300000",    // 5 minutes standard  
  "api1.odata.nextlink.poll.interval.ms": "5000",   // 5 seconds for pagination
  "api1.odata.deltalink.poll.interval.ms": "30000"  // 30 seconds for changes
}
```

### Balanced Approach
```json
{
  "api1.request.interval.ms": "120000",    // 2 minutes standard
  "api1.odata.nextlink.poll.interval.ms": "3000",   // 3 seconds for pagination  
  "api1.odata.deltalink.poll.interval.ms": "600000" // 10 minutes for incremental
}
```

## Performance Benefits

- **Faster Initial Sync**: Reduced time to process large datasets through aggressive nextLink polling
- **Reduced API Load**: Less frequent deltaLink polling reduces unnecessary API calls during idle periods
- **Optimal Resource Usage**: CPU and network resources are used efficiently based on current processing needs
- **Better User Experience**: Initial data loads complete faster, while ongoing updates remain responsive

## Monitoring

The connector logs the polling interval decisions at DEBUG level:

```
DEBUG Using nextLink poll interval 2000 ms for API api1
DEBUG Using deltaLink poll interval 600000 ms for API api1  
DEBUG Using standard poll interval for API api1 (link type: UNKNOWN)
```

## Backward Compatibility

This feature is fully backward compatible:
- Existing configurations without OData-specific intervals continue to work unchanged
- The standard `request.interval.ms` is used as the default for all polling scenarios
- Non-OData offset modes are unaffected by this feature

# OAuth2 Token Refresh Interval Configuration

## Problem Fixed
The OAuth2 token refresh interval was hardcoded to 30 minutes in the HttpSourceTask, which didn't allow users to adjust the refresh frequency based on their specific token expiry times or security requirements.

## Solution Implemented

### New Configuration Property
Added a new configurable property to control the OAuth2 token refresh interval:

**Property**: `oauth2.token.refresh.interval.minutes`
- **Type**: Integer
- **Default**: 30 minutes
- **Range**: 1 to 1440 minutes (1 minute to 24 hours)
- **Importance**: Medium
- **Description**: The interval in minutes for refreshing OAuth2 tokens

### Configuration Definition
```java
configDef.define(
    OAUTH2_TOKEN_REFRESH_INTERVAL_MINUTES,
    ConfigDef.Type.INT,
    30,
    ConfigDef.Range.between(1, 1440), // 1 minute to 24 hours
    ConfigDef.Importance.MEDIUM,
    "The interval in minutes for refreshing OAuth2 tokens. Defaults to 30 minutes"
);
```

### Implementation Changes

#### Before (Hardcoded):
```java
scheduler.scheduleAtFixedRate(() -> {
    try {
        authenticator.refreshToken();
    } catch (Exception e) {
        log.error("Failed to refresh OAuth2 token", e);
    }
}, 0, 30, TimeUnit.MINUTES); // Hardcoded 30 minutes
```

#### After (Configurable):
```java
int refreshIntervalMinutes = config.getOauth2TokenRefreshIntervalMinutes();
scheduler.scheduleAtFixedRate(() -> {
    try {
        authenticator.refreshToken();
    } catch (Exception e) {
        log.error("Failed to refresh OAuth2 token", e);
    }
}, 0, refreshIntervalMinutes, TimeUnit.MINUTES);

log.info("OAuth2 token refresh scheduled every {} minutes", refreshIntervalMinutes);
```

## Usage Examples

### Example 1: High-Security Environment (Frequent Refresh)
```json
{
  "name": "secure-oauth2-connector",
  "config": {
    "auth.type": "OAUTH2",
    "oauth2.token.url": "https://auth.example.com/oauth/token",
    "oauth2.client.id": "your-client-id",
    "oauth2.client.secret": "your-client-secret",
    "oauth2.token.refresh.interval.minutes": "5"
  }
}
```

### Example 2: Long-Lived Tokens (Less Frequent Refresh)
```json
{
  "name": "long-lived-oauth2-connector",
  "config": {
    "auth.type": "OAUTH2",
    "oauth2.token.url": "https://auth.example.com/oauth/token",
    "oauth2.client.id": "your-client-id",
    "oauth2.client.secret": "your-client-secret",
    "oauth2.token.refresh.interval.minutes": "120"
  }
}
```

### Example 3: Default Behavior (Backward Compatible)
```json
{
  "name": "default-oauth2-connector",
  "config": {
    "auth.type": "OAUTH2",
    "oauth2.token.url": "https://auth.example.com/oauth/token",
    "oauth2.client.id": "your-client-id",
    "oauth2.client.secret": "your-client-secret"
    // oauth2.token.refresh.interval.minutes defaults to 30
  }
}
```

## Configuration Guidelines

### Recommended Intervals by Use Case:

1. **High-Security Environments**: 5-15 minutes
   - Financial services, healthcare, government
   - Short-lived tokens with strict security requirements

2. **Standard Production**: 15-30 minutes (default)
   - Most enterprise applications
   - Balance between security and performance

3. **Development/Testing**: 30-60 minutes
   - Non-production environments
   - Reduce token refresh overhead during testing

4. **Long-Lived Tokens**: 60-240 minutes
   - APIs with 4+ hour token expiry
   - Batch processing scenarios

### Best Practices:

1. **Set refresh interval to 50-75% of token expiry time**
   - If tokens expire in 60 minutes, set refresh to 30-45 minutes
   - Provides buffer for network delays and processing time

2. **Consider your API rate limits**
   - Some OAuth2 providers have rate limits on token endpoints
   - More frequent refresh = more requests to token endpoint

3. **Monitor token refresh logs**
   - The connector logs refresh intervals and failures
   - Use logs to tune the interval based on actual token behavior

4. **Test with your OAuth2 provider**
   - Different providers have different token behaviors
   - Some providers may reject overly frequent refresh requests

## Validation and Error Handling

### Range Validation:
- **Minimum**: 1 minute (prevents excessive API calls)
- **Maximum**: 1440 minutes (24 hours, reasonable upper bound)
- **Invalid values** result in configuration validation errors

### Error Scenarios:
1. **Token refresh fails**: Error is logged, but connector continues
2. **Invalid interval**: Configuration validation prevents startup
3. **Zero or negative values**: Rejected by range validator

## Backward Compatibility

✅ **Fully Backward Compatible**
- Existing configurations without this property work unchanged
- Default value (30 minutes) matches previous behavior
- No breaking changes to API or functionality

## Monitoring and Logging

The connector provides enhanced logging for token refresh:

```
INFO  - OAuth2 token refresh scheduled every 15 minutes
INFO  - OAuth2 token refreshed successfully
ERROR - Failed to refresh OAuth2 token: <error details>
```

## Files Modified

1. **HttpSourceConnectorConfig.java**
   - Added `OAUTH2_TOKEN_REFRESH_INTERVAL_MINUTES` constant
   - Added configuration definition with validation
   - Added getter method `getOauth2TokenRefreshIntervalMinutes()`

2. **HttpSourceTask.java**
   - Updated `startScheduler()` method to use configurable interval
   - Added logging for refresh interval configuration

3. **oauth2-api-config.json** (example)
   - Added example usage of the new configuration property

## Testing Verification

✅ **Compilation**: Code compiles successfully  
✅ **Configuration**: Property validates correctly within range  
✅ **Default Behavior**: Works without specifying the property  
✅ **Custom Values**: Accepts valid custom intervals  
✅ **Range Validation**: Rejects values outside 1-1440 range  

This enhancement provides users with the flexibility to optimize OAuth2 token refresh based on their specific security requirements and token expiry patterns while maintaining full backward compatibility.
---
name: Bug Report
about: Create a report to help us improve
title: '[BUG] '
labels: ['bug', 'needs-triage']
assignees: ''
---

## Bug Description

A clear and concise description of what the bug is.

## Environment

**Connector Version:** (e.g., 1.0.0)
**Kafka Version:** (e.g., 7.6.0)
**Java Version:** (e.g., OpenJDK 11.0.20)
**Operating System:** (e.g., Ubuntu 20.04, macOS 13.0)
**Kafka Connect Mode:** (Standalone/Distributed)

## Configuration

```json
{
  "name": "your-connector-name",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    // Include relevant configuration here (remove sensitive data)
  }
}
```

## Steps to Reproduce

1. Configure the connector with the above settings
2. Start the connector
3. ...
4. See error

## Expected Behavior

A clear and concise description of what you expected to happen.

## Actual Behavior

A clear and concise description of what actually happened.

## Error Messages

### Connector Logs
```
Paste relevant log entries here
```

### Error Response
```
Include any error responses from APIs or Kafka Connect
```

## Additional Context

### HTTP API Details
- **API Endpoint:** https://api.example.com/endpoint
- **Authentication Type:** OAuth2/API Key/Basic/None
- **Response Format:** JSON/XML
- **Rate Limiting:** Yes/No

### Kafka Details
- **Topic Name:** your-topic-name
- **Partition Count:** 3
- **Replication Factor:** 2

### Network Configuration
- **Proxy:** Yes/No
- **SSL/TLS:** Yes/No
- **Firewall Restrictions:** Yes/No

## Troubleshooting Attempted

- [ ] Checked connector logs
- [ ] Verified API connectivity manually
- [ ] Tested configuration validation
- [ ] Reviewed documentation
- [ ] Searched existing issues

## Workaround

If you found a workaround, please describe it here.

## Additional Files

If applicable, add any additional files (logs, configurations, screenshots) that might help diagnose the issue.
---
name: Security Issue
about: Report a security vulnerability (use private channels for sensitive issues)
title: '[SECURITY] '
labels: ['security', 'needs-triage']
assignees: ''
---

⚠️ **SECURITY NOTICE** ⚠️

If this is a **high-severity security vulnerability**, please:
1. **DO NOT** create a public issue
2. Send details privately to: [security@your-org.com](mailto:security@your-org.com)
3. Include "SECURITY" in the subject line

For **general security improvements** or **low-risk issues**, continue with this template.

## Security Issue Type

- [ ] Vulnerability in dependencies
- [ ] Configuration security issue
- [ ] Authentication/authorization weakness
- [ ] Data exposure/privacy concern
- [ ] Input validation issue
- [ ] Cryptographic weakness
- [ ] Information disclosure
- [ ] Other security concern

## Severity Assessment

### Impact Level
- [ ] Critical - Remote code execution, data breach
- [ ] High - Privilege escalation, sensitive data exposure
- [ ] Medium - Information disclosure, denial of service
- [ ] Low - Security best practice improvement

### Exploitability
- [ ] Easy - No authentication required
- [ ] Medium - Requires authentication/specific conditions
- [ ] Hard - Requires complex setup or insider access

## Issue Description

Provide a clear description of the security issue.

## Environment

**Connector Version:** (e.g., 1.0.0)
**Kafka Version:** (e.g., 7.6.0)
**Java Version:** (e.g., OpenJDK 11.0.20)
**Deployment:** (Standalone/Distributed/Cloud)

## Affected Components

- [ ] Authentication mechanisms
- [ ] Field-level encryption
- [ ] HTTP client
- [ ] Configuration handling
- [ ] Error handling
- [ ] API chaining
- [ ] Performance optimization
- [ ] Dependencies

## Steps to Reproduce

1. Configure the connector with...
2. Set up the environment...
3. Perform action...
4. Observe security issue...

## Expected Security Behavior

What should happen from a security perspective.

## Actual Security Issue

What actually happens that represents a security concern.

## Impact Assessment

### Data at Risk
- [ ] Configuration data
- [ ] API credentials
- [ ] Sensitive field data
- [ ] Log data
- [ ] No sensitive data

### Systems at Risk
- [ ] Kafka Connect cluster
- [ ] Source HTTP APIs
- [ ] Kafka cluster
- [ ] Client applications
- [ ] Infrastructure

### Attack Vectors
- [ ] Network-based
- [ ] Configuration-based
- [ ] Authentication bypass
- [ ] Data interception
- [ ] Privilege escalation

## Mitigation Recommendations

### Immediate Workarounds
1. Temporary fixes or configurations to reduce risk
2. Alternative approaches

### Long-term Solutions
1. Code changes required
2. Configuration improvements
3. Process changes

## Security Best Practices

Suggest improvements to prevent similar issues:

- [ ] Input validation enhancements
- [ ] Authentication strengthening
- [ ] Configuration security
- [ ] Logging security
- [ ] Dependency management
- [ ] Documentation updates

## Additional Context

### Configuration (sanitized)
```json
{
  "relevant": "configuration",
  "with": "sensitive_data_removed"
}
```

### Error Messages (sanitized)
```
Relevant error messages with sensitive data removed
```

## References

- [Security documentation](link)
- [Related CVE](link)
- [Security standards](link)

## Disclosure Timeline

- **Discovery Date:** YYYY-MM-DD
- **Internal Report Date:** YYYY-MM-DD
- **Fix Target Date:** YYYY-MM-DD
- **Public Disclosure Date:** YYYY-MM-DD (after fix)
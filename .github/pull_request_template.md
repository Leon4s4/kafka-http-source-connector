## Description

Brief description of the changes in this PR.

## Type of Change

- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] 💥 Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] 📚 Documentation update
- [ ] 🔧 Configuration change
- [ ] 🧪 Test improvement
- [ ] 🔒 Security enhancement
- [ ] ⚡ Performance improvement

## Changes Made

- [ ] Core connector functionality
- [ ] Authentication mechanisms
- [ ] Error handling
- [ ] Performance optimization
- [ ] Field-level encryption
- [ ] API chaining
- [ ] Configuration
- [ ] Documentation
- [ ] Tests

## Testing

### Test Coverage
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Manual testing completed
- [ ] Performance tests (if applicable)
- [ ] Security tests (if applicable)

### Test Results
```
# Paste test execution results here
mvn test
```

## Documentation

- [ ] README updated (if needed)
- [ ] Configuration documentation updated
- [ ] Enterprise features documentation updated
- [ ] Examples added/updated
- [ ] Javadoc added/updated

## Security Considerations

- [ ] No hardcoded secrets or credentials
- [ ] Sensitive data properly encrypted
- [ ] Input validation implemented
- [ ] Authentication properly handled
- [ ] SSL/TLS configuration secure
- [ ] Dependencies scanned for vulnerabilities

## Performance Impact

- [ ] No significant performance regression
- [ ] Memory usage analyzed
- [ ] Response time impact minimal
- [ ] Caching considerations addressed
- [ ] Resource usage optimized

## Configuration Changes

### New Configuration Properties
List any new configuration properties:

```properties
# Example:
new.config.property=default_value
```

### Breaking Configuration Changes
List any configuration properties that changed:

```properties
# Old:
old.property.name=value

# New:
new.property.name=value
```

## Compatibility

- [ ] Backward compatible with existing configurations
- [ ] Tested with Kafka versions: 7.4.x, 7.5.x, 7.6.x
- [ ] Java 11 compatible
- [ ] No breaking API changes

## Deployment Notes

### Prerequisites
- [ ] Docker (for integration tests)
- [ ] Java 11+
- [ ] Maven 3.6+

### Installation Steps
1. Build: `mvn clean package`
2. Deploy: Copy JAR to Kafka Connect plugins directory
3. Configure: Use provided examples
4. Restart: Restart Kafka Connect cluster

## Checklist

### Code Quality
- [ ] Code follows project conventions
- [ ] All tests pass locally
- [ ] No compiler warnings
- [ ] Code is properly documented
- [ ] Error handling is comprehensive

### Security
- [ ] Security scan passes
- [ ] No credentials in code
- [ ] Proper input validation
- [ ] Secure defaults used

### Documentation
- [ ] Changes documented
- [ ] Examples provided
- [ ] Configuration reference updated

## Related Issues

Fixes #(issue number)
Relates to #(issue number)

## Additional Notes

Add any additional notes for reviewers here.

## Reviewer Checklist

### For Reviewers
- [ ] Code review completed
- [ ] Security review completed
- [ ] Documentation review completed
- [ ] Test coverage adequate
- [ ] Performance impact acceptable
- [ ] Configuration changes documented

### Final Approval
- [ ] All CI checks pass
- [ ] Security scans pass
- [ ] Manual testing completed
- [ ] Ready for merge
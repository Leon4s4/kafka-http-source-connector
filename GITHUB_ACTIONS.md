# GitHub Actions CI/CD Setup

This document describes the comprehensive GitHub Actions workflows configured for the Kafka HTTP Source Connector project.

## Workflows Overview

### 1. CI Workflow (`.github/workflows/ci.yml`)

**Triggers:**
- Push to `main` and `develop` branches
- Pull requests to `main` and `develop` branches

**Jobs:**
- **Test Suite**: Runs on Java 11 & 17 with unit and integration tests
- **Build Package**: Creates connector JAR and packages
- **Security Scan**: OWASP dependency vulnerability scanning
- **Code Quality**: SonarCloud analysis (PR only)
- **Performance Test**: Benchmark execution (PR only)
- **Compatibility Test**: Tests against multiple Kafka versions
- **Release Check**: Validates release readiness
- **PR Auto-Labeler**: Automatically labels PRs based on files changed
- **Failure Notification**: Comments on PRs when CI fails

**Features:**
- Matrix builds across Java versions
- Test result reporting
- Artifact uploads with 30-day retention
- Maven dependency caching
- TestContainers integration testing

### 2. Release Workflow (`.github/workflows/release.yml`)

**Triggers:**
- Git tags matching `v*` pattern
- Manual workflow dispatch with version input

**Jobs:**
- **Validate Release**: Version validation and full test suite
- **Build and Publish**: Creates GitHub release with assets
- **Notify Release**: Team notifications and discussions
- **Update Documentation**: Version updates in docs

**Release Assets:**
- Main connector JAR
- Fat JAR with dependencies
- Confluent Hub package
- SHA256 checksums
- Comprehensive release notes

### 3. Security Workflow (`.github/workflows/security.yml`)

**Triggers:**
- Daily schedule (2 AM UTC)
- Push to `main` branch
- PRs modifying dependencies or source code

**Jobs:**
- **Dependency Scan**: OWASP vulnerability scanning with SARIF upload
- **Secrets Scan**: TruffleHog for credential detection
- **License Scan**: License compliance checking
- **Code Security**: SpotBugs static analysis
- **Security Summary**: Consolidated security report

**Security Features:**
- SARIF integration with GitHub Security tab
- License compatibility checking
- Secrets detection in git history
- Security-focused static analysis
- Automated vulnerability reporting

## Configuration Files

### Labels Configuration (`.github/labeler.yml`)
Automatically applies labels to PRs based on modified files:
- `source`: Source code changes
- `tests`: Test modifications
- `documentation`: Documentation updates
- `config`: Configuration changes
- `ci`: CI/CD modifications
- `security`: Security-related changes
- `performance`: Performance improvements
- `dependencies`: Dependency updates

### PR Template (`.github/pull_request_template.md`)
Comprehensive template covering:
- Change description and type
- Testing requirements
- Documentation updates
- Security considerations
- Performance impact
- Configuration changes
- Compatibility notes
- Reviewer checklist

### Issue Templates (`.github/ISSUE_TEMPLATE/`)
- **Bug Report**: Detailed bug reporting with environment details
- **Feature Request**: Feature proposals with use cases
- **Security Issue**: Security vulnerability reporting (with private channel guidance)

### Security Configuration (`.github/spotbugs-security.xml`)
SpotBugs configuration focusing on:
- SQL injection detection
- Command injection prevention
- Path traversal vulnerabilities
- XSS protection
- Cryptographic security
- Authentication weaknesses
- Information disclosure prevention

## Usage Instructions

### For Contributors

#### Pull Request Process
1. Create feature branch from `main`
2. Make changes with appropriate tests
3. Push to GitHub - CI automatically runs
4. Review CI results and address any failures
5. Maintainers review and merge

#### Running Tests Locally
```bash
# Unit tests only
mvn test -Dtest="*Test" -Dtest.exclude="*IntegrationTest"

# Integration tests (requires Docker)
mvn test -Dtest="*IntegrationTest"

# Full build
mvn clean package
```

### For Maintainers

#### Creating Releases
1. **Automatic (Recommended):**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. **Manual:**
   - Go to GitHub Actions
   - Run "Release" workflow
   - Enter version number

#### Security Monitoring
- Check daily security scan results
- Review vulnerability reports in artifacts
- Monitor GitHub Security tab for alerts
- Address high/critical vulnerabilities promptly

#### Managing Dependencies
- Monitor Dependabot PRs
- Review license compliance reports
- Update dependencies regularly
- Validate security scan results

## CI/CD Features

### Automated Testing
- **Unit Tests**: Fast feedback on code changes
- **Integration Tests**: End-to-end validation with TestContainers
- **Performance Tests**: Benchmark regression detection
- **Security Tests**: Vulnerability and compliance scanning
- **Compatibility Tests**: Multi-version Kafka compatibility

### Quality Gates
- All tests must pass
- Security scans must not find high/critical issues
- License compliance must be maintained
- Code coverage thresholds
- No hardcoded secrets detected

### Artifact Management
- Automatic JAR building and publishing
- Confluent Hub package creation
- Checksums for integrity verification
- 30-day artifact retention
- Release asset organization

### Notifications
- PR comments for CI status
- Security scan summaries
- Performance benchmark results
- Release announcements
- Failure notifications

## Environment Variables and Secrets

Required secrets for full functionality:
- `GITHUB_TOKEN` (automatic)
- `SONAR_TOKEN` (for SonarCloud - optional)
- `CONFLUENT_HUB_TOKEN` (for Confluent Hub publishing - optional)

## Monitoring and Maintenance

### Regular Tasks
1. **Weekly**: Review security scan results
2. **Monthly**: Update GitHub Actions versions
3. **Quarterly**: Review and update workflows
4. **As needed**: Adjust quality gates and thresholds

### Key Metrics to Monitor
- Build success rate
- Test execution time
- Security scan results
- Dependency update frequency
- Release cycle time

### Troubleshooting

#### Common Issues
1. **TestContainer failures**: Ensure Docker is available in CI
2. **Dependency conflicts**: Check for version mismatches
3. **Security scan failures**: Review and update vulnerable dependencies
4. **Build timeouts**: Optimize build performance or increase timeouts

#### Debugging Steps
1. Check GitHub Actions logs
2. Review artifact uploads
3. Validate configuration syntax
4. Test locally with same Java/Maven versions

## Best Practices

### For Development
- Keep PRs focused and small
- Include tests for all changes
- Update documentation with code changes
- Follow security best practices
- Use conventional commit messages

### For CI/CD
- Cache dependencies for faster builds
- Use matrix builds for compatibility testing
- Implement proper error handling
- Maintain workflow documentation
- Regular security and dependency updates

This comprehensive CI/CD setup ensures high-quality, secure, and reliable releases of the Kafka HTTP Source Connector while providing excellent developer experience and automated quality assurance.
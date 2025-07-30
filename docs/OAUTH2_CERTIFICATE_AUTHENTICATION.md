# OAuth2 Certificate-Based Authentication

This document describes the OAuth2 certificate-based authentication feature in the HTTP Source Connector, which allows using PFX/PKCS12 certificates for client authentication instead of client secrets.

## Overview

OAuth2 certificate-based authentication provides a more secure alternative to client secret authentication by using digital certificates for client authentication. This method is particularly useful in enterprise environments where certificate-based security is preferred or required.

## Configuration

### Required Configuration Properties

| Property | Description | Example |
|----------|-------------|---------|
| `auth.type` | Must be set to `OAUTH2` | `OAUTH2` |
| `oauth2.client.auth.mode` | Must be set to `CERTIFICATE` | `CERTIFICATE` |
| `oauth2.token.url` | OAuth2 token endpoint URL | `https://auth.example.com/oauth2/token` |
| `oauth2.client.id` | OAuth2 client identifier | `your-client-id` |
| `oauth2.client.certificate.path` | Path to the PFX/PKCS12 certificate file | `/path/to/certificate.pfx` |

### Optional Configuration Properties

| Property | Description | Default | Example |
|----------|-------------|---------|---------|
| `oauth2.client.certificate.password` | Password for the certificate file | `null` | `cert-password` |
| `oauth2.token.property` | Name of the access token property in the response | `access_token` | `access_token` |
| `oauth2.client.scope` | OAuth2 scope parameter | `any` | `read write` |

## Certificate Requirements

### Supported Certificate Formats

- **PKCS12 (.pfx, .p12)**: Primary supported format
- The certificate must contain both the client certificate and private key
- Password-protected and password-less certificates are supported

### Certificate Generation

To generate a test certificate for development purposes:

```bash
# Generate a private key
openssl genrsa -out client.key 2048

# Generate a certificate signing request
openssl req -new -key client.key -out client.csr

# Generate a self-signed certificate (for testing)
openssl x509 -req -in client.csr -signkey client.key -out client.crt -days 365

# Combine into PKCS12 format
openssl pkcs12 -export -in client.crt -inkey client.key -out client.pfx -name "client-cert"
```

## Example Configuration

### Basic Certificate Authentication

```json
{
  "name": "oauth2-certificate-http-source",
  "config": {
    "connector.class": "io.confluent.connect.http.HttpSourceConnector",
    "tasks.max": "1",
    "http.api.base.url": "https://api.example.com",
    "apis.num": "1",
    "api1.http.api.path": "/api/data",
    "api1.topics": "oauth2-certificate-topic",
    "http.api.1.endpoint": "/api/data",
    "http.api.1.topic": "oauth2-certificate-topic",
    "http.api.1.method": "GET",
    "http.poll.interval.ms": "30000",
    
    "auth.type": "OAUTH2",
    "oauth2.client.auth.mode": "CERTIFICATE",
    "oauth2.token.url": "https://auth.example.com/oauth2/token",
    "oauth2.client.id": "your-client-id",
    "oauth2.client.certificate.path": "/path/to/your/certificate.pfx",
    "oauth2.client.certificate.password": "your-certificate-password",
    "oauth2.token.property": "access_token",
    "oauth2.client.scope": "read write"
  }
}
```

### Password-less Certificate

```json
{
  "auth.type": "OAUTH2",
  "oauth2.client.auth.mode": "CERTIFICATE",
  "oauth2.token.url": "https://auth.example.com/oauth2/token",
  "oauth2.client.id": "your-client-id",
  "oauth2.client.certificate.path": "/path/to/your/certificate.pfx"
}
```

## How It Works

1. **Initialization**: The connector loads the PFX certificate and initializes an SSL context with the client certificate
2. **Token Request**: When requesting an OAuth2 token, the connector uses mTLS (mutual TLS) authentication with the certificate
3. **API Requests**: The obtained Bearer token is used for subsequent API requests
4. **Token Refresh**: Tokens are automatically refreshed when they expire, using the same certificate authentication

## Authentication Flow

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│                 │    │                 │    │                 │
│   HTTP Source   │    │  OAuth2 Server  │    │   API Server    │
│   Connector     │    │                 │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         │ 1. mTLS Certificate   │                       │
         │    Authentication     │                       │
         ├──────────────────────→│                       │
         │                       │                       │
         │ 2. Access Token       │                       │
         │←──────────────────────┤                       │
         │                       │                       │
         │ 3. API Request with Bearer Token              │
         ├─────────────────────────────────────────────→│
         │                       │                       │
         │ 4. API Response       │                       │
         │←─────────────────────────────────────────────┤
```

## Security Considerations

### Production Environment

- **Certificate Storage**: Store certificates in secure locations with appropriate file permissions
- **Certificate Validation**: Ensure certificates are issued by trusted Certificate Authorities
- **Password Protection**: Use strong passwords for certificate files
- **Certificate Rotation**: Implement regular certificate rotation procedures
- **TLS Verification**: Enable proper TLS hostname verification in production

### Development Environment

- **Self-signed Certificates**: Acceptable for testing but should not be used in production
- **Test Certificate Passwords**: Use simple passwords for development certificates
- **Certificate Expiry**: Monitor certificate expiration dates

## Troubleshooting

### Common Issues

1. **Certificate Not Found**
   ```
   Error: Failed to initialize certificate-based authentication
   Cause: Certificate file not found at specified path
   Solution: Verify the certificate path is correct and file exists
   ```

2. **Invalid Certificate Password**
   ```
   Error: Failed to load certificate
   Cause: Incorrect certificate password
   Solution: Verify the certificate password is correct
   ```

3. **Unsupported Certificate Format**
   ```
   Error: Invalid keystore format
   Cause: Certificate is not in PKCS12 format
   Solution: Convert certificate to PKCS12 format
   ```

4. **Certificate Expired**
   ```
   Error: Certificate has expired
   Cause: Client certificate is expired
   Solution: Renew or replace the client certificate
   ```

### Debug Configuration

Enable debug logging to troubleshoot authentication issues:

```properties
log4j.logger.io.confluent.connect.http.auth.OAuth2CertificateAuthenticator=DEBUG
```

## Comparison with Client Secret Authentication

| Feature | Client Secret | Certificate-based |
|---------|---------------|-------------------|
| Security | Password-based | PKI-based |
| Rotation | Manual secret update | Certificate renewal |
| Storage | Configuration file | Certificate store |
| Complexity | Low | Medium |
| Enterprise Compliance | Basic | High |

## Migration from Client Secret

To migrate from client secret to certificate-based authentication:

1. Obtain a client certificate from your identity provider
2. Update the connector configuration:
   - Change `oauth2.client.auth.mode` from `HEADER` or `URL` to `CERTIFICATE`
   - Add `oauth2.client.certificate.path` and `oauth2.client.certificate.password`
   - Remove `oauth2.client.secret`
3. Restart the connector
4. Verify authentication is working correctly

## Testing

The feature includes comprehensive test coverage:

- **Unit Tests**: Configuration validation and factory behavior
- **Integration Tests**: End-to-end authentication flow
- **Certificate Tests**: Various certificate scenarios and error handling

## Related Documentation

- [OAuth2 Configuration Guide](OAUTH2_OPTIMIZATION.md)
- [Authentication Configuration](CONFIGURATION_EXAMPLES.md)
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)

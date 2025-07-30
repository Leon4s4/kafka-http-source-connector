package io.confluent.connect.http.auth;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating HTTP authenticators based on configuration.
 */
public class HttpAuthenticatorFactory {
    
    private static final Logger log = LoggerFactory.getLogger(HttpAuthenticatorFactory.class);
    
    /**
     * Creates an appropriate HttpAuthenticator based on the authentication type specified in config
     * 
     * @param config The connector configuration
     * @return An HttpAuthenticator instance for the specified authentication type
     */
    public static HttpAuthenticator create(HttpSourceConnectorConfig config) {
        HttpSourceConnectorConfig.AuthType authType = config.getAuthType();
        
        log.info("Creating authenticator for auth type: {}", authType);
        
        switch (authType) {
            case NONE:
                return new NoAuthenticator();
            
            case BASIC:
                return new BasicAuthenticator(config.getConnectionUser(), config.getConnectionPassword());
            
            case BEARER:
                return new BearerTokenAuthenticator(config.getBearerToken());
            
            case OAUTH2:
                HttpSourceConnectorConfig.OAuth2ClientAuthMode authMode = config.getOauth2ClientAuthMode();
                if (authMode == HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE) {
                    return new OAuth2CertificateAuthenticator(
                        config.getOauth2TokenUrl(),
                        config.getOauth2ClientId(),
                        config.getOauth2ClientCertificatePath(),
                        config.getOauth2ClientCertificatePassword(),
                        config.getOauth2TokenProperty(),
                        config.getOauth2ClientScope()
                    );
                } else {
                    return new OAuth2Authenticator(
                        config.getOauth2TokenUrl(),
                        config.getOauth2ClientId(),
                        config.getOauth2ClientSecret(),
                        config.getOauth2TokenProperty(),
                        config.getOauth2ClientScope(),
                        authMode
                    );
                }
            
            case API_KEY:
                return new ApiKeyAuthenticator(
                    config.getApiKeyName(),
                    config.getApiKeyValue(),
                    config.getApiKeyLocation()
                );
            
            default:
                throw new IllegalArgumentException("Unsupported authentication type: " + authType);
        }
    }
}
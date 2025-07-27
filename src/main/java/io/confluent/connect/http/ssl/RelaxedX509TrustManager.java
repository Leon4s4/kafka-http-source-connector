package io.confluent.connect.http.ssl;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relaxed trust manager that accepts self-signed certificates and other certificate issues.
 * Should only be used in development or testing environments.
 */
public class RelaxedX509TrustManager implements X509TrustManager {
    
    private static final Logger log = LoggerFactory.getLogger(RelaxedX509TrustManager.class);
    
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // Accept all client certificates
        log.debug("Accepting client certificate chain (relaxed validation): authType={}, chain length={}", 
                 authType, chain != null ? chain.length : 0);
    }
    
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
        // Accept all server certificates
        if (chain != null && chain.length > 0) {
            X509Certificate serverCert = chain[0];
            log.warn("Accepting server certificate without validation (relaxed mode): " +
                    "subject='{}', issuer='{}', authType='{}'",
                    serverCert.getSubjectDN().getName(),
                    serverCert.getIssuerDN().getName(),
                    authType);
        } else {
            log.warn("Accepting empty certificate chain (relaxed validation)");
        }
    }
    
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // Return empty array to accept all issuers
        return new X509Certificate[0];
    }
}

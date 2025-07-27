package io.confluent.connect.http.ssl;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trust manager that disables all certificate validation.
 * WARNING: This should NEVER be used in production environments.
 */
public class DisabledX509TrustManager implements X509TrustManager {
    
    private static final Logger log = LoggerFactory.getLogger(DisabledX509TrustManager.class);
    
    public DisabledX509TrustManager() {
        log.warn("*** WARNING: Certificate validation is completely disabled! ***");
        log.warn("*** This should NEVER be used in production environments! ***");
    }
    
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // No validation - accept everything
        log.debug("Skipping client certificate validation (disabled): authType={}, chain length={}", 
                 authType, chain != null ? chain.length : 0);
    }
    
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
        // No validation - accept everything
        log.debug("Skipping server certificate validation (disabled): authType={}, chain length={}", 
                 authType, chain != null ? chain.length : 0);
    }
    
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // Return empty array
        return new X509Certificate[0];
    }
}

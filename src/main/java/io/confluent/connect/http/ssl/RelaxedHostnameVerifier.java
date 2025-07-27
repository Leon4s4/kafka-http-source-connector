package io.confluent.connect.http.ssl;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relaxed hostname verifier that allows connections to hosts with certificate name mismatches.
 * Should only be used in development or testing environments.
 */
public class RelaxedHostnameVerifier implements HostnameVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(RelaxedHostnameVerifier.class);
    
    @Override
    public boolean verify(String hostname, SSLSession session) {
        // Log the mismatch but allow connection
        try {
            String peerHost = session.getPeerHost();
            if (!hostname.equals(peerHost)) {
                log.warn("Hostname verification mismatch: expected '{}', got '{}' - allowing connection due to relaxed verification",
                        hostname, peerHost);
            }
        } catch (Exception e) {
            log.warn("Error during relaxed hostname verification for '{}': {}", hostname, e.getMessage());
        }
        
        return true; // Always allow connection
    }
}

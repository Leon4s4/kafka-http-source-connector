package io.confluent.connect.http.ssl;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hostname verifier that implements certificate pinning.
 * Verifies that the server certificate matches one of the pinned certificate hashes.
 */
public class PinnedHostnameVerifier implements HostnameVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(PinnedHostnameVerifier.class);
    
    private final String apiId;
    private final List<String> pinnedCertificates;
    
    public PinnedHostnameVerifier(String apiId, List<String> pinnedCertificates) {
        this.apiId = apiId;
        this.pinnedCertificates = pinnedCertificates;
    }
    
    @Override
    public boolean verify(String hostname, SSLSession session) {
        if (pinnedCertificates == null || pinnedCertificates.isEmpty()) {
            log.warn("No certificate pins configured for API '{}' - rejecting connection", apiId);
            return false;
        }
        
        try {
            // Get the server certificate chain
            Certificate[] certificates = session.getPeerCertificates();
            if (certificates == null || certificates.length == 0) {
                log.warn("No certificates in chain for hostname '{}'", hostname);
                return false;
            }
            
            // Check each certificate in the chain against pins
            for (Certificate cert : certificates) {
                if (cert instanceof X509Certificate) {
                    X509Certificate x509Cert = (X509Certificate) cert;
                    String certHash = calculateCertificateHash(x509Cert);
                    
                    if (pinnedCertificates.contains(certHash)) {
                        log.debug("Certificate pin matched for hostname '{}' (API: {})", hostname, apiId);
                        return true;
                    }
                    
                    // Also check public key hash (SPKI pinning)
                    String publicKeyHash = calculatePublicKeyHash(x509Cert);
                    if (pinnedCertificates.contains(publicKeyHash)) {
                        log.debug("Public key pin matched for hostname '{}' (API: {})", hostname, apiId);
                        return true;
                    }
                }
            }
            
            log.warn("Certificate pinning failed for hostname '{}' (API: {}): no matching pins found", 
                    hostname, apiId);
            return false;
            
        } catch (Exception e) {
            log.error("Error during certificate pinning verification for hostname '{}' (API: {}): {}", 
                     hostname, apiId, e.getMessage());
            return false;
        }
    }
    
    private String calculateCertificateHash(X509Certificate certificate) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] der = certificate.getEncoded();
        byte[] hash = md.digest(der);
        return "sha256/" + Base64.getEncoder().encodeToString(hash);
    }
    
    private String calculatePublicKeyHash(X509Certificate certificate) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] spki = certificate.getPublicKey().getEncoded();
        byte[] hash = md.digest(spki);
        return "sha256/" + Base64.getEncoder().encodeToString(hash);
    }
}

package io.confluent.connect.http.ssl;

import javax.net.ssl.X509TrustManager;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trust manager that implements certificate pinning.
 * Only trusts certificates that match the configured certificate or public key pins.
 */
public class PinnedX509TrustManager implements X509TrustManager {
    
    private static final Logger log = LoggerFactory.getLogger(PinnedX509TrustManager.class);
    
    private final List<String> pinnedCertificates;
    
    public PinnedX509TrustManager(List<String> pinnedCertificates) {
        if (pinnedCertificates == null || pinnedCertificates.isEmpty()) {
            throw new IllegalArgumentException("No certificate pins provided. Certificate pinning requires at least one pin.");
        }
        this.pinnedCertificates = pinnedCertificates;
        log.info("Certificate pinning enabled with {} pins", pinnedCertificates.size());
    }
    
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // For client certificates, we still validate against pins
        validateCertificateChain(chain, "client", authType);
    }
    
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateCertificateChain(chain, "server", authType);
    }
    
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // Return empty array since we're doing pinning
        return new X509Certificate[0];
    }
    
    private void validateCertificateChain(X509Certificate[] chain, String type, String authType) 
            throws CertificateException {
        
        if (pinnedCertificates == null || pinnedCertificates.isEmpty()) {
            throw new CertificateException("No certificate pins configured - rejecting connection");
        }
        
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain");
        }
        
        try {
            // Check each certificate in the chain
            for (X509Certificate cert : chain) {
                // Calculate certificate hash (full certificate)
                String certHash = calculateCertificateHash(cert);
                if (pinnedCertificates.contains(certHash)) {
                    log.debug("Certificate pin matched for {} certificate (authType: {})", type, authType);
                    return; // Pin matched - trust the chain
                }
                
                // Calculate public key hash (SPKI pinning)
                String publicKeyHash = calculatePublicKeyHash(cert);
                if (pinnedCertificates.contains(publicKeyHash)) {
                    log.debug("Public key pin matched for {} certificate (authType: {})", type, authType);
                    return; // Pin matched - trust the chain
                }
            }
            
            // No pins matched
            log.warn("Certificate pinning validation failed for {} certificate chain (authType: {}): " +
                    "no matching pins found in chain of {} certificates", type, authType, chain.length);
            
            if (log.isDebugEnabled()) {
                for (int i = 0; i < chain.length; i++) {
                    X509Certificate cert = chain[i];
                    try {
                        String certHash = calculateCertificateHash(cert);
                        String publicKeyHash = calculatePublicKeyHash(cert);
                        log.debug("Certificate[{}]: subject='{}', certHash='{}', publicKeyHash='{}'",
                                i, cert.getSubjectDN().getName(), certHash, publicKeyHash);
                    } catch (Exception e) {
                        log.debug("Certificate[{}]: subject='{}', hash calculation failed: {}",
                                i, cert.getSubjectDN().getName(), e.getMessage());
                    }
                }
                log.debug("Configured pins: {}", pinnedCertificates);
            }
            
            throw new CertificateException("Certificate pinning validation failed: no matching pins found");
            
        } catch (CertificateException e) {
            throw e; // Re-throw certificate exceptions
        } catch (Exception e) {
            log.error("Error during certificate pinning validation", e);
            throw new CertificateException("Certificate pinning validation error: " + e.getMessage(), e);
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

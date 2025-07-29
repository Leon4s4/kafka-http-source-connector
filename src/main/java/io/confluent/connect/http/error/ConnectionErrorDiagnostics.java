package io.confluent.connect.http.error;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced connection error diagnostics with detailed error analysis and troubleshooting suggestions.
 * Provides comprehensive error reporting for HTTP connection issues, SSL/TLS problems, and network failures.
 */
public class ConnectionErrorDiagnostics {
    
    private static final Logger log = LoggerFactory.getLogger(ConnectionErrorDiagnostics.class);
    
    private final HttpSourceConnectorConfig config;
    
    public ConnectionErrorDiagnostics(HttpSourceConnectorConfig config) {
        this.config = config;
    }
    
    /**
     * Analyze connection error and provide detailed diagnostics.
     */
    public EnhancedErrorReport analyzeConnectionError(Throwable error, String url, Map<String, String> requestContext) {
        EnhancedErrorReport report = new EnhancedErrorReport();
        
        try {
            // Extract root cause
            Throwable rootCause = getRootCause(error);
            
            // Classify error type
            ErrorClassification classification = classifyError(rootCause);
            report.setErrorClassification(classification);
            
            // Add basic error information
            report.setOriginalError(error.getClass().getSimpleName() + ": " + error.getMessage());
            report.setRootCause(rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage());
            report.setUrl(url);
            report.setTimestamp(System.currentTimeMillis());
            
            // Add detailed diagnostics based on error type
            switch (classification.getCategory()) {
                case SSL_TLS_ERROR:
                    addSSLDiagnostics(report, rootCause, url);
                    break;
                case CONNECTION_ERROR:
                    addConnectionDiagnostics(report, rootCause, url);
                    break;
                case TIMEOUT_ERROR:
                    addTimeoutDiagnostics(report, rootCause, url);
                    break;
                case DNS_ERROR:
                    addDNSDiagnostics(report, rootCause, url);
                    break;
                case AUTHENTICATION_ERROR:
                    addAuthenticationDiagnostics(report, rootCause, url);
                    break;
                default:
                    addGenericDiagnostics(report, rootCause, url);
            }
            
            // Add configuration context
            addConfigurationContext(report);
            
            // Add troubleshooting suggestions
            addTroubleshootingSuggestions(report, classification);
            
            // Log detailed error
            logDetailedError(report);
            
        } catch (Exception e) {
            log.error("Failed to analyze connection error", e);
            report.setDiagnosticsError("Failed to analyze error: " + e.getMessage());
        }
        
        return report;
    }
    
    /**
     * Get the root cause of an exception chain.
     */
    private Throwable getRootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
    
    /**
     * Classify the error into categories.
     */
    private ErrorClassification classifyError(Throwable error) {
        String errorClass = error.getClass().getName();
        String message = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        
        // SSL/TLS Errors
        if (error instanceof SSLException || 
            error instanceof SSLHandshakeException ||
            error instanceof CertificateException ||
            errorClass.contains("SSL") ||
            message.contains("ssl") || message.contains("tls") || 
            message.contains("certificate") || message.contains("handshake")) {
            
            return new ErrorClassification(ErrorCategory.SSL_TLS_ERROR, determineSeverity(error), 
                "SSL/TLS connection error - certificate or encryption issues");
        }
        
        // Connection Errors
        if (error instanceof ConnectionClosedException ||
            error instanceof ConnectException ||
            message.contains("connection") && (message.contains("closed") || message.contains("refused"))) {
            return new ErrorClassification(ErrorCategory.CONNECTION_ERROR, determineSeverity(error),
                "Connection establishment or maintenance error");
        }
        
        // Timeout Errors
        if (error instanceof SocketTimeoutException ||
            message.contains("timeout") || message.contains("timed out")) {
            return new ErrorClassification(ErrorCategory.TIMEOUT_ERROR, determineSeverity(error),
                "Request timeout - server not responding within configured time");
        }
        
        // DNS Errors
        if (error instanceof UnknownHostException ||
            message.contains("unknown host") || message.contains("name resolution")) {
            return new ErrorClassification(ErrorCategory.DNS_ERROR, determineSeverity(error),
                "DNS resolution failed - hostname cannot be resolved");
        }
        
        // Authentication Errors
        if (message.contains("unauthorized") || message.contains("authentication") || 
            message.contains("401") || message.contains("403")) {
            return new ErrorClassification(ErrorCategory.AUTHENTICATION_ERROR, determineSeverity(error),
                "Authentication or authorization failure");
        }
        
        // Default
        return new ErrorClassification(ErrorCategory.GENERIC_ERROR, determineSeverity(error),
            "Unclassified connection error");
    }
    
    /**
     * Add SSL/TLS specific diagnostics.
     */
    private void addSSLDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> sslDiagnostics = new HashMap<>();
        
        // SSL Configuration
        sslDiagnostics.put("SSL_ENABLED", String.valueOf(config.isHttpsSslEnabled()));
        sslDiagnostics.put("SSL_PROTOCOL", config.getHttpsSslProtocol());
        
        // Check for specific SSL errors
        String message = error.getMessage() != null ? error.getMessage() : "";
        
        if (message.contains("certificate")) {
            sslDiagnostics.put("ERROR_TYPE", "Certificate Error");
            sslDiagnostics.put("LIKELY_CAUSE", "Invalid, expired, or untrusted certificate");
            
            if (message.contains("self-signed")) {
                sslDiagnostics.put("SPECIFIC_ISSUE", "Self-signed certificate not trusted");
                sslDiagnostics.put("SOLUTION", "Add certificate to truststore or disable certificate validation");
            } else if (message.contains("expired")) {
                sslDiagnostics.put("SPECIFIC_ISSUE", "Certificate has expired");
                sslDiagnostics.put("SOLUTION", "Update certificate on server or in truststore");
            } else if (message.contains("hostname")) {
                sslDiagnostics.put("SPECIFIC_ISSUE", "Hostname verification failed");
                sslDiagnostics.put("SOLUTION", "Check certificate SAN/CN matches hostname");
            }
        } else if (message.contains("handshake")) {
            sslDiagnostics.put("ERROR_TYPE", "SSL Handshake Error");
            sslDiagnostics.put("LIKELY_CAUSE", "SSL/TLS protocol mismatch or cipher suite incompatibility");
            sslDiagnostics.put("SOLUTION", "Check SSL protocol version and cipher suites");
        } else if (message.contains("BadPaddingException")) {
            sslDiagnostics.put("ERROR_TYPE", "SSL Key/Certificate Format Error");
            sslDiagnostics.put("LIKELY_CAUSE", "Incorrect keystore password or corrupted certificate/key");
            sslDiagnostics.put("SOLUTION", "Verify keystore password and certificate format");
        }
        
        // Configuration recommendations
        sslDiagnostics.put("KEYSTORE_CONFIGURED", "false"); // Default since not in current config
        sslDiagnostics.put("TRUSTSTORE_CONFIGURED", "false"); // Default since not in current config
        
        report.addDiagnosticSection("SSL_TLS_DIAGNOSTICS", sslDiagnostics);
    }
    
    /**
     * Add connection specific diagnostics.
     */
    private void addConnectionDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> connectionDiagnostics = new HashMap<>();
        
        connectionDiagnostics.put("URL", url);
        connectionDiagnostics.put("ERROR_TYPE", "Connection Error");
        
        String message = error.getMessage() != null ? error.getMessage() : "";
        
        if (error instanceof ConnectionClosedException) {
            connectionDiagnostics.put("SPECIFIC_ISSUE", "Connection closed by server");
            connectionDiagnostics.put("LIKELY_CAUSE", "Server terminated connection during request processing");
            connectionDiagnostics.put("SOLUTIONS", "Check server health, network stability, request timeout settings");
        } else if (message.contains("refused")) {
            connectionDiagnostics.put("SPECIFIC_ISSUE", "Connection refused by server");
            connectionDiagnostics.put("LIKELY_CAUSE", "Server not running, firewall blocking, or wrong port");
            connectionDiagnostics.put("SOLUTIONS", "Verify server is running, check firewall rules, confirm port number");
        } else if (message.contains("reset")) {
            connectionDiagnostics.put("SPECIFIC_ISSUE", "Connection reset by peer");
            connectionDiagnostics.put("LIKELY_CAUSE", "Network interruption or server forcibly closed connection");
            connectionDiagnostics.put("SOLUTIONS", "Check network stability, server logs, and retry mechanisms");
        }
        
        // Add proxy information if configured
        if (config.getHttpProxyHost() != null) {
            connectionDiagnostics.put("PROXY_CONFIGURED", "true");
            connectionDiagnostics.put("PROXY_HOST", config.getHttpProxyHost());
            connectionDiagnostics.put("PROXY_PORT", String.valueOf(config.getHttpProxyPort()));
        } else {
            connectionDiagnostics.put("PROXY_CONFIGURED", "false");
        }
        
        report.addDiagnosticSection("CONNECTION_DIAGNOSTICS", connectionDiagnostics);
    }
    
    /**
     * Add timeout specific diagnostics.
     */
    private void addTimeoutDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> timeoutDiagnostics = new HashMap<>();
        
        timeoutDiagnostics.put("ERROR_TYPE", "Timeout Error");
        timeoutDiagnostics.put("LIKELY_CAUSE", "Server response time exceeds configured timeout");
        
        // Add timeout configuration (these would need to be added to config if not present)
        timeoutDiagnostics.put("CONFIGURED_TIMEOUTS", "Check http.client.connection.timeout.ms and http.client.read.timeout.ms");
        timeoutDiagnostics.put("SOLUTIONS", "Increase timeout values, optimize server performance, or check network latency");
        
        report.addDiagnosticSection("TIMEOUT_DIAGNOSTICS", timeoutDiagnostics);
    }
    
    /**
     * Add DNS specific diagnostics.
     */
    private void addDNSDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> dnsDiagnostics = new HashMap<>();
        
        dnsDiagnostics.put("ERROR_TYPE", "DNS Resolution Error");
        dnsDiagnostics.put("LIKELY_CAUSE", "Hostname cannot be resolved to IP address");
        dnsDiagnostics.put("SOLUTIONS", "Check hostname spelling, DNS server configuration, network connectivity");
        
        // Extract hostname from URL
        try {
            java.net.URL urlObj = new java.net.URL(url);
            dnsDiagnostics.put("HOSTNAME", urlObj.getHost());
        } catch (Exception e) {
            dnsDiagnostics.put("HOSTNAME", "Unable to parse hostname from URL");
        }
        
        report.addDiagnosticSection("DNS_DIAGNOSTICS", dnsDiagnostics);
    }
    
    /**
     * Add authentication specific diagnostics.
     */
    private void addAuthenticationDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> authDiagnostics = new HashMap<>();
        
        authDiagnostics.put("ERROR_TYPE", "Authentication Error");
        authDiagnostics.put("AUTH_TYPE", config.getAuthType().name());
        authDiagnostics.put("LIKELY_CAUSE", "Invalid credentials or expired authentication token");
        authDiagnostics.put("SOLUTIONS", "Verify credentials, check token expiration, review authentication configuration");
        
        report.addDiagnosticSection("AUTHENTICATION_DIAGNOSTICS", authDiagnostics);
    }
    
    /**
     * Add generic diagnostics.
     */
    private void addGenericDiagnostics(EnhancedErrorReport report, Throwable error, String url) {
        Map<String, String> genericDiagnostics = new HashMap<>();
        
        genericDiagnostics.put("ERROR_TYPE", "Generic Connection Error");
        genericDiagnostics.put("ERROR_CLASS", error.getClass().getName());
        genericDiagnostics.put("LIKELY_CAUSE", "Network or configuration issue");
        genericDiagnostics.put("SOLUTIONS", "Check network connectivity, server status, and configuration parameters");
        
        report.addDiagnosticSection("GENERIC_DIAGNOSTICS", genericDiagnostics);
    }
    
    /**
     * Add configuration context.
     */
    private void addConfigurationContext(EnhancedErrorReport report) {
        Map<String, String> configContext = new HashMap<>();
        
        configContext.put("BASE_URL", config.getHttpApiBaseUrl());
        configContext.put("AUTH_TYPE", config.getAuthType().name());
        configContext.put("SSL_ENABLED", String.valueOf(config.isHttpsSslEnabled()));
        configContext.put("TASKS_MAX", String.valueOf(config.getTasksMax()));
        
        if (config.getHttpProxyHost() != null) {
            configContext.put("PROXY_HOST", config.getHttpProxyHost());
            configContext.put("PROXY_PORT", String.valueOf(config.getHttpProxyPort()));
        }
        
        report.addDiagnosticSection("CONFIGURATION_CONTEXT", configContext);
    }
    
    /**
     * Add troubleshooting suggestions based on error classification.
     */
    private void addTroubleshootingSuggestions(EnhancedErrorReport report, ErrorClassification classification) {
        switch (classification.getCategory()) {
            case SSL_TLS_ERROR:
                report.addSuggestion("1. Verify SSL certificate is valid and trusted");
                report.addSuggestion("2. Check SSL protocol version compatibility (TLS 1.2/1.3)");
                report.addSuggestion("3. Configure truststore with server certificate if self-signed");
                report.addSuggestion("4. Verify keystore password and format if client certificates are used");
                report.addSuggestion("5. Test SSL connection using: openssl s_client -connect hostname:port");
                break;
                
            case CONNECTION_ERROR:
                report.addSuggestion("1. Verify server is running and accessible");
                report.addSuggestion("2. Check firewall rules and network connectivity");
                report.addSuggestion("3. Confirm correct hostname and port number");
                report.addSuggestion("4. Review server logs for connection issues");
                report.addSuggestion("5. Test connectivity using: telnet hostname port");
                break;
                
            case TIMEOUT_ERROR:
                report.addSuggestion("1. Increase connection and read timeout values");
                report.addSuggestion("2. Check server performance and response times");
                report.addSuggestion("3. Analyze network latency between connector and server");
                report.addSuggestion("4. Consider implementing request retry mechanisms");
                break;
                
            case DNS_ERROR:
                report.addSuggestion("1. Verify hostname spelling and DNS configuration");
                report.addSuggestion("2. Test DNS resolution using: nslookup hostname");
                report.addSuggestion("3. Check /etc/hosts file for local overrides");
                report.addSuggestion("4. Configure alternative DNS servers if needed");
                break;
                
            case AUTHENTICATION_ERROR:
                report.addSuggestion("1. Verify authentication credentials are correct");
                report.addSuggestion("2. Check if authentication tokens have expired");
                report.addSuggestion("3. Review API key permissions and scope");
                report.addSuggestion("4. Test authentication separately using curl or similar tool");
                break;
                
            default:
                report.addSuggestion("1. Check connector and server logs for additional details");
                report.addSuggestion("2. Verify network connectivity and configuration");
                report.addSuggestion("3. Test connection manually using appropriate tools");
                report.addSuggestion("4. Contact system administrator if issue persists");
        }
    }
    
    /**
     * Determine error severity.
     */
    private ErrorSeverity determineSeverity(Throwable error) {
        if (error instanceof SSLException || error instanceof CertificateException) {
            return ErrorSeverity.HIGH;
        } else if (error instanceof ConnectionClosedException || error instanceof ConnectException) {
            return ErrorSeverity.MEDIUM;
        } else if (error instanceof SocketTimeoutException) {
            return ErrorSeverity.MEDIUM;
        } else if (error instanceof UnknownHostException) {
            return ErrorSeverity.HIGH;
        }
        return ErrorSeverity.MEDIUM;
    }
    
    /**
     * Log detailed error information.
     */
    private void logDetailedError(EnhancedErrorReport report) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== CONNECTION ERROR DIAGNOSTICS ===\n");
        logMessage.append("Error Classification: ").append(report.getErrorClassification().getDescription()).append("\n");
        logMessage.append("Severity: ").append(report.getErrorClassification().getSeverity()).append("\n");
        logMessage.append("URL: ").append(report.getUrl()).append("\n");
        logMessage.append("Root Cause: ").append(report.getRootCause()).append("\n");
        
        // Add diagnostic sections
        report.getDiagnosticSections().forEach((section, details) -> {
            logMessage.append("\n--- ").append(section).append(" ---\n");
            details.forEach((key, value) -> 
                logMessage.append(key).append(": ").append(value).append("\n"));
        });
        
        // Add suggestions
        if (!report.getSuggestions().isEmpty()) {
            logMessage.append("\n--- TROUBLESHOOTING SUGGESTIONS ---\n");
            report.getSuggestions().forEach(suggestion -> 
                logMessage.append(suggestion).append("\n"));
        }
        
        logMessage.append("=== END DIAGNOSTICS ===\n");
        
        log.error(logMessage.toString());
    }
    
    // Supporting classes
    public enum ErrorCategory {
        SSL_TLS_ERROR,
        CONNECTION_ERROR,
        TIMEOUT_ERROR,
        DNS_ERROR,
        AUTHENTICATION_ERROR,
        GENERIC_ERROR
    }
    
    public enum ErrorSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    public static class ErrorClassification {
        private final ErrorCategory category;
        private final ErrorSeverity severity;
        private final String description;
        
        public ErrorClassification(ErrorCategory category, ErrorSeverity severity, String description) {
            this.category = category;
            this.severity = severity;
            this.description = description;
        }
        
        public ErrorCategory getCategory() { return category; }
        public ErrorSeverity getSeverity() { return severity; }
        public String getDescription() { return description; }
    }
}
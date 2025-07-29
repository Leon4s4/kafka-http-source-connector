package io.confluent.connect.http.error;

import java.util.*;

/**
 * Enhanced error report containing detailed diagnostics and troubleshooting information.
 */
public class EnhancedErrorReport {
    
    private String originalError;
    private String rootCause;
    private String url;
    private long timestamp;
    private ConnectionErrorDiagnostics.ErrorClassification errorClassification;
    private String diagnosticsError;
    
    private final Map<String, Map<String, String>> diagnosticSections = new HashMap<>();
    private final List<String> suggestions = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();
    
    public EnhancedErrorReport() {
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getters and Setters
    public String getOriginalError() {
        return originalError;
    }
    
    public void setOriginalError(String originalError) {
        this.originalError = originalError;
    }
    
    public String getRootCause() {
        return rootCause;
    }
    
    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public ConnectionErrorDiagnostics.ErrorClassification getErrorClassification() {
        return errorClassification;
    }
    
    public void setErrorClassification(ConnectionErrorDiagnostics.ErrorClassification errorClassification) {
        this.errorClassification = errorClassification;
    }
    
    public String getDiagnosticsError() {
        return diagnosticsError;
    }
    
    public void setDiagnosticsError(String diagnosticsError) {
        this.diagnosticsError = diagnosticsError;
    }
    
    public Map<String, Map<String, String>> getDiagnosticSections() {
        return diagnosticSections;
    }
    
    public List<String> getSuggestions() {
        return suggestions;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    // Utility methods
    public void addDiagnosticSection(String sectionName, Map<String, String> diagnostics) {
        this.diagnosticSections.put(sectionName, new HashMap<>(diagnostics));
    }
    
    public void addSuggestion(String suggestion) {
        this.suggestions.add(suggestion);
    }
    
    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    /**
     * Generate a human-readable summary of the error report.
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        
        summary.append("CONNECTION ERROR SUMMARY\n");
        summary.append("========================\n");
        
        if (errorClassification != null) {
            summary.append("Error Type: ").append(errorClassification.getDescription()).append("\n");
            summary.append("Severity: ").append(errorClassification.getSeverity()).append("\n");
        }
        
        if (url != null) {
            summary.append("URL: ").append(url).append("\n");
        }
        
        if (rootCause != null) {
            summary.append("Root Cause: ").append(rootCause).append("\n");
        }
        
        if (!suggestions.isEmpty()) {
            summary.append("\nTroubleshooting Suggestions:\n");
            for (int i = 0; i < suggestions.size(); i++) {
                summary.append(suggestions.get(i)).append("\n");
            }
        }
        
        return summary.toString();
    }
    
    /**
     * Generate detailed technical report.
     */
    public String generateDetailedReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("DETAILED CONNECTION ERROR REPORT\n");
        report.append("================================\n");
        report.append("Timestamp: ").append(new Date(timestamp)).append("\n");
        
        if (errorClassification != null) {
            report.append("Classification: ").append(errorClassification.getCategory()).append("\n");
            report.append("Severity: ").append(errorClassification.getSeverity()).append("\n");
            report.append("Description: ").append(errorClassification.getDescription()).append("\n");
        }
        
        report.append("Original Error: ").append(originalError != null ? originalError : "N/A").append("\n");
        report.append("Root Cause: ").append(rootCause != null ? rootCause : "N/A").append("\n");
        report.append("URL: ").append(url != null ? url : "N/A").append("\n");
        
        // Add diagnostic sections
        if (!diagnosticSections.isEmpty()) {
            report.append("\nDIAGNOSTIC DETAILS\n");
            report.append("==================\n");
            
            diagnosticSections.forEach((sectionName, details) -> {
                report.append("\n--- ").append(sectionName).append(" ---\n");
                details.forEach((key, value) -> 
                    report.append(key).append(": ").append(value).append("\n"));
            });
        }
        
        // Add suggestions
        if (!suggestions.isEmpty()) {
            report.append("\nTROUBLESHOOTING SUGGESTIONS\n");
            report.append("===========================\n");
            suggestions.forEach(suggestion -> 
                report.append(suggestion).append("\n"));
        }
        
        // Add metadata
        if (!metadata.isEmpty()) {
            report.append("\nADDITIONAL METADATA\n");
            report.append("===================\n");
            metadata.forEach((key, value) -> 
                report.append(key).append(": ").append(value).append("\n"));
        }
        
        if (diagnosticsError != null) {
            report.append("\nDIAGNOSTICS ERROR\n");
            report.append("=================\n");
            report.append(diagnosticsError).append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * Check if the error report indicates a critical issue.
     */
    public boolean isCritical() {
        return errorClassification != null && 
               errorClassification.getSeverity() == ConnectionErrorDiagnostics.ErrorSeverity.CRITICAL;
    }
    
    /**
     * Check if the error is SSL/TLS related.
     */
    public boolean isSSLError() {
        return errorClassification != null && 
               errorClassification.getCategory() == ConnectionErrorDiagnostics.ErrorCategory.SSL_TLS_ERROR;
    }
    
    /**
     * Check if the error is connection related.
     */
    public boolean isConnectionError() {
        return errorClassification != null && 
               errorClassification.getCategory() == ConnectionErrorDiagnostics.ErrorCategory.CONNECTION_ERROR;
    }
    
    /**
     * Get specific diagnostic value.
     */
    public String getDiagnosticValue(String section, String key) {
        Map<String, String> sectionData = diagnosticSections.get(section);
        return sectionData != null ? sectionData.get(key) : null;
    }
    
    @Override
    public String toString() {
        return generateSummary();
    }
}
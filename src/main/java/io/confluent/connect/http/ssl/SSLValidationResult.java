package io.confluent.connect.http.ssl;

import java.util.List;

/**
 * Result of SSL configuration validation.
 * Contains validation status, messages, errors, and warnings.
 */
public class SSLValidationResult {
    
    private final boolean valid;
    private final String message;
    private final List<String> errors;
    private final List<String> warnings;
    private final long validationTimestamp;
    
    public SSLValidationResult(boolean valid, String message, List<String> errors, List<String> warnings) {
        this.valid = valid;
        this.message = message;
        this.errors = errors != null ? errors : List.of();
        this.warnings = warnings != null ? warnings : List.of();
        this.validationTimestamp = System.currentTimeMillis();
    }
    
    public boolean isValid() {
        return valid;
    }
    
    public String getMessage() {
        return message;
    }
    
    public List<String> getErrors() {
        return errors;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public long getValidationTimestamp() {
        return validationTimestamp;
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public int getWarningCount() {
        return warnings.size();
    }
    
    /**
     * Get a summary of the validation result.
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("SSL Validation: ").append(valid ? "VALID" : "INVALID");
        
        if (hasErrors()) {
            summary.append(" (").append(getErrorCount()).append(" error(s))");
        }
        
        if (hasWarnings()) {
            summary.append(" (").append(getWarningCount()).append(" warning(s))");
        }
        
        return summary.toString();
    }
    
    /**
     * Get a detailed report of all issues.
     */
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        report.append("SSL Configuration Validation Report\n");
        report.append("=====================================\n");
        report.append("Status: ").append(valid ? "VALID" : "INVALID").append("\n");
        report.append("Message: ").append(message).append("\n");
        report.append("Validation Time: ").append(new java.util.Date(validationTimestamp)).append("\n\n");
        
        if (hasErrors()) {
            report.append("ERRORS (").append(getErrorCount()).append("):\n");
            for (int i = 0; i < errors.size(); i++) {
                report.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
            }
            report.append("\n");
        }
        
        if (hasWarnings()) {
            report.append("WARNINGS (").append(getWarningCount()).append("):\n");
            for (int i = 0; i < warnings.size(); i++) {
                report.append("  ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
            }
            report.append("\n");
        }
        
        if (!hasErrors() && !hasWarnings()) {
            report.append("No issues found.\n");
        }
        
        return report.toString();
    }
    
    @Override
    public String toString() {
        return String.format("SSLValidationResult{valid=%s, errors=%d, warnings=%d, message='%s'}",
                           valid, getErrorCount(), getWarningCount(), message);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        SSLValidationResult that = (SSLValidationResult) o;
        
        if (valid != that.valid) return false;
        if (validationTimestamp != that.validationTimestamp) return false;
        if (!message.equals(that.message)) return false;
        if (!errors.equals(that.errors)) return false;
        return warnings.equals(that.warnings);
    }
    
    @Override
    public int hashCode() {
        int result = (valid ? 1 : 0);
        result = 31 * result + message.hashCode();
        result = 31 * result + errors.hashCode();
        result = 31 * result + warnings.hashCode();
        result = 31 * result + (int) (validationTimestamp ^ (validationTimestamp >>> 32));
        return result;
    }
}

package io.confluent.connect.http.health;

/**
 * Represents the health status of an API or the overall connector.
 * Contains status information, metrics, and diagnostic details.
 */
public class HealthStatus {
    
    public enum Status {
        UP,         // Service is healthy and operating normally
        DOWN,       // Service is unhealthy or not available
        DEGRADED,   // Service is operational but with reduced performance
        UNKNOWN     // Health status cannot be determined
    }
    
    private final Status status;
    private final String message;
    private final long requestCount;
    private final double successRate;
    private final double averageResponseTime;
    private final String circuitBreakerState;
    private final long timestamp;
    
    public HealthStatus(Status status, String message, long requestCount, 
                       double successRate, double averageResponseTime, 
                       String circuitBreakerState, long timestamp) {
        this.status = status;
        this.message = message;
        this.requestCount = requestCount;
        this.successRate = successRate;
        this.averageResponseTime = averageResponseTime;
        this.circuitBreakerState = circuitBreakerState;
        this.timestamp = timestamp;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public long getRequestCount() {
        return requestCount;
    }
    
    public double getSuccessRate() {
        return successRate;
    }
    
    public double getAverageResponseTime() {
        return averageResponseTime;
    }
    
    public String getCircuitBreakerState() {
        return circuitBreakerState;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("HealthStatus{status=%s, message='%s', requestCount=%d, " +
                           "successRate=%.2f%%, avgResponseTime=%.2fms, circuitBreaker=%s, timestamp=%d}",
                           status, message, requestCount, successRate, averageResponseTime, 
                           circuitBreakerState, timestamp);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        HealthStatus that = (HealthStatus) o;
        
        if (requestCount != that.requestCount) return false;
        if (Double.compare(that.successRate, successRate) != 0) return false;
        if (Double.compare(that.averageResponseTime, averageResponseTime) != 0) return false;
        if (timestamp != that.timestamp) return false;
        if (status != that.status) return false;
        if (!message.equals(that.message)) return false;
        return circuitBreakerState.equals(that.circuitBreakerState);
    }
    
    @Override
    public int hashCode() {
        int result;
        long temp;
        result = status.hashCode();
        result = 31 * result + message.hashCode();
        result = 31 * result + (int) (requestCount ^ (requestCount >>> 32));
        temp = Double.doubleToLongBits(successRate);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(averageResponseTime);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + circuitBreakerState.hashCode();
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        return result;
    }
}

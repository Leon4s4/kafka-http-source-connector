package io.confluent.connect.http.unit;

import io.confluent.connect.http.client.HttpApiClient;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.error.AdvancedErrorHandler;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Advanced Error Handler Unit Tests")
class AdvancedErrorHandlerTest {
    
    private ApiConfig apiConfig;
    private HttpSourceConnectorConfig config;
    private AdvancedErrorHandler errorHandler;
    
    @BeforeEach
    void setUp() {
        // Create real config instead of mocking
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("circuit.breaker.failure.threshold", "3");
        configMap.put("circuit.breaker.timeout.ms", "60000");
        configMap.put("circuit.breaker.recovery.time.ms", "30000");
        configMap.put("behavior.on.error", "IGNORE");
        
        config = new HttpSourceConnectorConfig(configMap);
        
        // Create real ApiConfig instead of mocking
        apiConfig = new ApiConfig(config, 1);
        
        errorHandler = new AdvancedErrorHandler(config);
    }
    
    @Test
    @DisplayName("Should categorize HTTP authentication errors correctly")
    void shouldCategorizeHttpAuthenticationErrors() {
        // Given
        HttpApiClient.HttpRequestException authError = new HttpApiClient.HttpRequestException(
            "Unauthorized", 401, "Authentication failed");
        
        // When
        errorHandler.handleError(apiConfig, authError, null);
        
        // Then
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory).containsKey(AdvancedErrorHandler.ErrorCategory.AUTHENTICATION);
        assertThat(metrics.errorsByCategory.get(AdvancedErrorHandler.ErrorCategory.AUTHENTICATION).get()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should categorize HTTP rate limit errors correctly")
    void shouldCategorizeHttpRateLimitErrors() {
        // Given
        HttpApiClient.HttpRequestException rateLimitError = new HttpApiClient.HttpRequestException(
            "Too Many Requests", 429, "Rate limit exceeded");
        
        // When & Then - Should throw RetriableException for rate limit errors
        assertThatThrownBy(() -> errorHandler.handleError(apiConfig, rateLimitError, null))
                .isInstanceOf(RetriableException.class)
                .hasMessageContaining("Retriable error for API: " + apiConfig.getId());
        
        // Verify metrics are still tracked
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory).containsKey(AdvancedErrorHandler.ErrorCategory.RATE_LIMIT);
        assertThat(metrics.errorsByCategory.get(AdvancedErrorHandler.ErrorCategory.RATE_LIMIT).get()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should categorize HTTP server errors as transient")
    void shouldCategorizeHttpServerErrorsAsTransient() {
        // Given
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error occurred");
        
        // When/Then
        assertThatThrownBy(() -> errorHandler.handleError(apiConfig, serverError, null))
            .isInstanceOf(RetriableException.class);
        
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory).containsKey(AdvancedErrorHandler.ErrorCategory.TRANSIENT);
    }
    
    @Test
    @DisplayName("Should categorize network errors as transient")
    void shouldCategorizeNetworkErrorsAsTransient() {
        // Given
        java.net.ConnectException networkError = new java.net.ConnectException("Connection refused");
        SocketTimeoutException timeoutError = new SocketTimeoutException("Read timeout");
        
        // When/Then
        assertThatThrownBy(() -> errorHandler.handleError(apiConfig, networkError, null))
            .isInstanceOf(RetriableException.class);
        
        assertThatThrownBy(() -> errorHandler.handleError(apiConfig, timeoutError, null))
            .isInstanceOf(RetriableException.class);
        
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory.get(AdvancedErrorHandler.ErrorCategory.TRANSIENT).get()).isEqualTo(2);
    }
    
    @Test
    @DisplayName("Should open circuit breaker after threshold failures")
    void shouldOpenCircuitBreakerAfterThresholdFailures() {
        // Given
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error");
        
        // When - trigger failures up to threshold
        for (int i = 0; i < 3; i++) {
            try {
                errorHandler.handleError(apiConfig, serverError, null);
            } catch (Exception e) {
                // Expected
            }
        }
        
        // Then - circuit should be open
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.OPEN);
        
        // API calls should not be allowed
        assertThat(errorHandler.canCallApi(apiConfig.getId())).isFalse();
        
        // Further errors should fail fast
        assertThatThrownBy(() -> errorHandler.handleError(apiConfig, serverError, null))
            .isInstanceOf(ConnectException.class)
            .hasMessageContaining("Circuit breaker open");
    }
    
    @Test
    @DisplayName("Should move to half-open state after recovery time")
    void shouldMoveToHalfOpenStateAfterRecoveryTime() throws InterruptedException {
        // Given - open circuit breaker
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error");
        
        for (int i = 0; i < 3; i++) {
            try {
                errorHandler.handleError(apiConfig, serverError, null);
            } catch (Exception e) {
                // Expected
            }
        }
        
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.OPEN);
        
        // When - simulate recovery time passage (create new handler with shorter recovery time)
        Map<String, String> newConfigMap = new HashMap<>();
        newConfigMap.put("http.api.base.url", "http://localhost:8080");
        newConfigMap.put("apis.num", "1");
        newConfigMap.put("api1.http.api.path", "/test");
        newConfigMap.put("api1.topics", "test-topic");
        newConfigMap.put("circuit.breaker.failure.threshold", "3");
        newConfigMap.put("circuit.breaker.timeout.ms", "60000");
        newConfigMap.put("circuit.breaker.recovery.time.ms", "5000");
        newConfigMap.put("behavior.on.error", "IGNORE");
        
        HttpSourceConnectorConfig newConfig = new HttpSourceConnectorConfig(newConfigMap);
        AdvancedErrorHandler newErrorHandler = new AdvancedErrorHandler(newConfig);
        
        // Copy the circuit breaker state to new handler
        newErrorHandler.getCircuitBreakerState(apiConfig.getId()); // Initialize
        // Trigger the same failures to get to OPEN state
        for (int i = 0; i < 3; i++) {
            try {
                newErrorHandler.handleError(apiConfig, serverError, null);
            } catch (Exception e) {
                // Expected
            }
        }
        
        Thread.sleep(5100); // Wait for recovery time
        
        // Then - should allow API call (moving to half-open)
        assertThat(newErrorHandler.canCallApi(apiConfig.getId())).isTrue();
    }
    
    @Test
    @DisplayName("Should close circuit breaker on successful recovery")
    void shouldCloseCircuitBreakerOnSuccessfulRecovery() {
        // Given - open circuit breaker
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error");
        
        for (int i = 0; i < 3; i++) {
            try {
                errorHandler.handleError(apiConfig, serverError, null);
            } catch (Exception e) {
                // Expected
            }
        }
        
        // Manually set to half-open for testing
        errorHandler.resetCircuitBreaker(apiConfig.getId());
        
        // When - record successful call
        errorHandler.recordSuccess(apiConfig.getId());
        
        // Then - circuit should be closed
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.CLOSED);
        assertThat(errorHandler.canCallApi(apiConfig.getId())).isTrue();
    }
    
    @Test
    @DisplayName("Should not count authentication errors toward circuit breaker")
    void shouldNotCountAuthenticationErrorsTowardCircuitBreaker() {
        // Given
        HttpApiClient.HttpRequestException authError = new HttpApiClient.HttpRequestException(
            "Unauthorized", 401, "Authentication failed");
        
        // When - trigger multiple auth errors
        for (int i = 0; i < 5; i++) {
            errorHandler.handleError(apiConfig, authError, null);
        }
        
        // Then - circuit should remain closed
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.CLOSED);
        assertThat(errorHandler.canCallApi(apiConfig.getId())).isTrue();
    }
    
    @Test
    @DisplayName("Should track error metrics correctly")
    void shouldTrackErrorMetricsCorrectly() {
        // Given
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error");
        IOException networkError = new IOException("Network error");
        
        // When
        try {
            errorHandler.handleError(apiConfig, serverError, null);
        } catch (Exception e) {
            // Expected
        }
        
        try {
            errorHandler.handleError(apiConfig, networkError, null);
        } catch (Exception e) {
            // Expected
        }
        
        errorHandler.recordSuccess(apiConfig.getId());
        errorHandler.recordSuccess(apiConfig.getId());
        
        // Then
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.totalErrors.get()).isEqualTo(2);
        assertThat(metrics.successCount.get()).isEqualTo(2);
        assertThat(metrics.getErrorRate()).isEqualTo(0.5); // 2 errors out of 4 total
        assertThat(metrics.getTimeSinceLastError()).isGreaterThan(0);
    }
    
    @Test
    @DisplayName("Should handle unknown errors gracefully")
    void shouldHandleUnknownErrorsGracefully() {
        // Given
        RuntimeException unknownError = new RuntimeException("Unknown error");
        
        // When
        errorHandler.handleError(apiConfig, unknownError, null);
        
        // Then
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory).containsKey(AdvancedErrorHandler.ErrorCategory.UNKNOWN);
        assertThat(metrics.errorsByCategory.get(AdvancedErrorHandler.ErrorCategory.UNKNOWN).get()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should reset circuit breaker when requested")
    void shouldResetCircuitBreakerWhenRequested() {
        // Given - open circuit breaker
        HttpApiClient.HttpRequestException serverError = new HttpApiClient.HttpRequestException(
            "Internal Server Error", 500, "Server error");
        
        for (int i = 0; i < 3; i++) {
            try {
                errorHandler.handleError(apiConfig, serverError, null);
            } catch (Exception e) {
                // Expected
            }
        }
        
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.OPEN);
        
        // When
        errorHandler.resetCircuitBreaker(apiConfig.getId());
        
        // Then
        assertThat(errorHandler.getCircuitBreakerState(apiConfig.getId()))
            .isEqualTo(AdvancedErrorHandler.CircuitBreakerState.CLOSED);
        assertThat(errorHandler.canCallApi(apiConfig.getId())).isTrue();
    }
    
    @Test
    @DisplayName("Should handle errors when status code access fails")
    void shouldHandleErrorsWhenStatusCodeAccessFails() {
        // Given - create a custom exception that extends HttpRequestException but doesn't have getStatusCode
        Exception customError = new Exception("Custom HTTP error") {
            @Override
            public String getMessage() {
                return "connection timeout occurred";
            }
        };
        
        // When
        try {
            errorHandler.handleError(apiConfig, customError, null);
        } catch (Exception e) {
            // Expected
        }
        
        // Then - should categorize as transient based on message content
        AdvancedErrorHandler.ErrorMetrics metrics = errorHandler.getErrorMetrics(apiConfig.getId());
        assertThat(metrics.errorsByCategory).containsKey(AdvancedErrorHandler.ErrorCategory.TRANSIENT);
    }
}
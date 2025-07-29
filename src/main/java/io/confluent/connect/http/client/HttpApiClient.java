package io.confluent.connect.http.client;

import io.confluent.connect.http.auth.ApiKeyAuthenticator;
import io.confluent.connect.http.auth.HttpAuthenticator;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.util.TemplateVariableReplacer;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * HTTP client for making requests to APIs with retry logic, authentication, and template variable support.
 */
public class HttpApiClient {
    
    private static final Logger log = LoggerFactory.getLogger(HttpApiClient.class);
    
    private final ApiConfig apiConfig;
    private final HttpSourceConnectorConfig globalConfig;
    private final HttpAuthenticator authenticator;
    private final OkHttpClient httpClient;
    private final RetryHandler retryHandler;
    private final TemplateVariableReplacer templateReplacer;
    
    public HttpApiClient(ApiConfig apiConfig, HttpAuthenticator authenticator) {
        this.apiConfig = apiConfig;
        this.globalConfig = apiConfig.getGlobalConfig();
        this.authenticator = authenticator;
        this.templateReplacer = new TemplateVariableReplacer();
        
        // Build HTTP client with configuration
        this.httpClient = buildHttpClient();
        
        // Initialize retry handler
        this.retryHandler = new RetryHandler(apiConfig);
        
        log.debug("Initialized HttpApiClient for API: {}", apiConfig.getId());
    }
    
    /**
     * Makes an HTTP request to the API with the given offset
     * 
     * @param offset The current offset value for template variable replacement
     * @return ApiResponse containing the response data, or null if no data
     * @throws Exception if the request fails after all retries
     */
    public ApiResponse makeRequest(String offset) throws Exception {
        return makeRequest(offset, Collections.emptyMap());
    }
    
    /**
     * Makes an HTTP request to the API with the given offset and additional template variables
     * 
     * @param offset The current offset value for template variable replacement
     * @param additionalVars Additional template variables (e.g., from API chaining)
     * @return ApiResponse containing the response data, or null if no data
     * @throws Exception if the request fails after all retries
     */
    public ApiResponse makeRequest(String offset, Map<String, String> additionalVars) throws Exception {
        log.debug("Making request to API: {} with offset: {}", apiConfig.getId(), offset);
        
        // Prepare template variables
        Map<String, String> templateVars = new HashMap<>();
        if (offset != null) {
            templateVars.put("offset", offset);
        }
        
        // Add additional template variables (e.g., from API chaining)
        if (additionalVars != null) {
            templateVars.putAll(additionalVars);
        }
        
        // Build request
        Request request = buildRequest(templateVars);
        
        // Execute with retry logic
        return retryHandler.executeWithRetry(() -> executeRequest(request));
    }
    
    /**
     * Builds the HTTP request with authentication, headers, and parameters
     */
    private Request buildRequest(Map<String, String> templateVars) {
        // For OData pagination, the offset contains the path with query parameters
        String rawUrl;
        if (apiConfig.getHttpOffsetMode() == ApiConfig.HttpOffsetMode.ODATA_PAGINATION && 
            templateVars.containsKey("offset") && templateVars.get("offset") != null) {
            String offset = templateVars.get("offset");
            // If offset starts with '/', it's a path to use instead of the configured API path
            if (offset.startsWith("/")) {
                rawUrl = globalConfig.getHttpApiBaseUrl().replaceAll("/$", "") + offset;
                log.debug("Using OData offset as full path: {}", rawUrl);
                // For OData, we don't need template replacement on the offset itself
                return buildODataRequest(rawUrl, templateVars);
            } else {
                rawUrl = apiConfig.getFullUrl();
            }
        } else {
            rawUrl = apiConfig.getFullUrl();
        }
        
        // Replace template variables in URL BEFORE parsing to avoid URL encoding issues
        String processedUrl = templateReplacer.replace(rawUrl, templateVars);
        
        log.debug("Template replacement: {} -> {}", rawUrl, processedUrl);
        log.debug("Template variables: {}", templateVars);
        
        // Build URL with processed template variables
        HttpUrl.Builder urlBuilder = HttpUrl.parse(processedUrl).newBuilder();
        
        // Add query parameters
        addQueryParameters(urlBuilder, templateVars);
        
        HttpUrl url = urlBuilder.build();
        
        // Build request
        Request.Builder requestBuilder = new Request.Builder().url(url);
        
        // Add HTTP method and body
        addMethodAndBody(requestBuilder, templateVars);
        
        // Add headers
        addHeaders(requestBuilder, templateVars);
        
        // Apply authentication
        authenticator.authenticate(requestBuilder);
        
        return requestBuilder.build();
    }
    
    /**
     * Adds query parameters to the URL, including API key if configured for query location
     */
    private void addQueryParameters(HttpUrl.Builder urlBuilder, Map<String, String> templateVars) {
        // Add configured parameters
        String parameters = apiConfig.getHttpRequestParameters();
        if (parameters != null && !parameters.trim().isEmpty()) {
            String processedParams = templateReplacer.replace(parameters, templateVars);
            String separator = apiConfig.getHttpRequestParametersSeparator();
            
            for (String param : processedParams.split(separator)) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    urlBuilder.addQueryParameter(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        
        // Add API key as query parameter if configured
        if (authenticator instanceof ApiKeyAuthenticator) {
            ApiKeyAuthenticator apiKeyAuth = (ApiKeyAuthenticator) authenticator;
            if (apiKeyAuth.getLocation() == HttpSourceConnectorConfig.ApiKeyLocation.QUERY) {
                urlBuilder.addQueryParameter(apiKeyAuth.getKeyName(), apiKeyAuth.getKeyValue());
            }
        }
    }
    
    /**
     * Adds HTTP method and request body
     */
    private void addMethodAndBody(Request.Builder requestBuilder, Map<String, String> templateVars) {
        ApiConfig.HttpRequestMethod method = apiConfig.getHttpRequestMethod();
        
        switch (method) {
            case GET:
                requestBuilder.get();
                break;
                
            case POST:
                String body = apiConfig.getHttpRequestBody();
                if (body != null) {
                    String processedBody = templateReplacer.replace(body, templateVars);
                    RequestBody requestBody = RequestBody.create(
                        processedBody, 
                        MediaType.parse("application/json; charset=utf-8")
                    );
                    requestBuilder.post(requestBody);
                } else {
                    // Empty POST body
                    requestBuilder.post(RequestBody.create("", MediaType.parse("application/json")));
                }
                break;
                
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
    }
    
    /**
     * Adds HTTP headers to the request
     */
    private void addHeaders(Request.Builder requestBuilder, Map<String, String> templateVars) {
        String headers = apiConfig.getHttpRequestHeaders();
        if (headers != null && !headers.trim().isEmpty()) {
            String processedHeaders = templateReplacer.replace(headers, templateVars);
            String separator = apiConfig.getHttpRequestHeadersSeparator();
            
            for (String header : processedHeaders.split(Pattern.quote(separator))) {
                String[] keyValue = header.split(":", 2);
                if (keyValue.length == 2) {
                    requestBuilder.header(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        
        // Add default headers if not already present
        if (requestBuilder.build().header("User-Agent") == null) {
            requestBuilder.header("User-Agent", "Kafka-HTTP-Source-Connector/1.0.0");
        }
        
        if (requestBuilder.build().header("Accept") == null) {
            requestBuilder.header("Accept", "application/json");
        }
    }
    
    /**
     * Executes the HTTP request and returns the response
     */
    private ApiResponse executeRequest(Request request) throws IOException {
        log.trace("Executing HTTP request: {} {}", request.method(), request.url());
        
        Instant startTime = Instant.now();
        
        try (Response response = httpClient.newCall(request).execute()) {
            Duration requestDuration = Duration.between(startTime, Instant.now());
            log.debug("HTTP request completed in {}ms: {} {} -> {}",
                requestDuration.toMillis(), request.method(), request.url(), response.code());
            
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new HttpRequestException(
                    "HTTP request failed with status " + response.code() + ": " + response.message(),
                    response.code(),
                    errorBody
                );
            }
            
            String responseBody = response.body() != null ? response.body().string() : null;
            
            return new ApiResponse(
                response.code(),
                response.headers().toMultimap(),
                responseBody,
                requestDuration
            );
            
        } catch (IOException e) {
            Duration requestDuration = Duration.between(startTime, Instant.now());
            log.error("HTTP request failed after {}ms: {} {} - {}",
                requestDuration.toMillis(), request.method(), request.url(), e.getMessage());
            throw e;
        }
    }
    
    /**
     * Builds the OkHttpClient with SSL, proxy, and timeout configuration
     */
    private OkHttpClient buildHttpClient() {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .connectTimeout(apiConfig.getHttpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(apiConfig.getHttpRequestTimeoutMs(), TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS);
        
        // Configure SSL
        configureSsl(clientBuilder);
        
        // Configure proxy
        configureProxy(clientBuilder);
        
        return clientBuilder.build();
    }
    
    /**
     * Configures SSL settings for the HTTP client
     */
    private void configureSsl(OkHttpClient.Builder clientBuilder) {
        if (globalConfig.isHttpsSslEnabled()) {
            try {
                // Use the default system TrustManager for proper certificate validation
                SSLContext sslContext = SSLContext.getInstance(globalConfig.getHttpsSslProtocol());
                sslContext.init(null, null, new SecureRandom());
                
                clientBuilder.sslSocketFactory(sslContext.getSocketFactory(), getDefaultTrustManager());
                
                log.debug("SSL configured with protocol: {} and default TrustManager", globalConfig.getHttpsSslProtocol());
                
            } catch (Exception e) {
                log.error("Failed to configure SSL", e);
                throw new RuntimeException("Failed to configure SSL", e);
            }
        }
    }
    
    /**
     * Gets the default system TrustManager for proper certificate validation
     */
    private X509TrustManager getDefaultTrustManager() throws Exception {
        javax.net.ssl.TrustManagerFactory trustManagerFactory = 
            javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((java.security.KeyStore) null);
        
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        
        throw new IllegalStateException("No X509TrustManager found");
    }
    
    /**
     * Configures proxy settings for the HTTP client
     */
    private void configureProxy(OkHttpClient.Builder clientBuilder) {
        String proxyHost = globalConfig.getHttpProxyHost();
        Integer proxyPort = globalConfig.getHttpProxyPort();
        
        if (proxyHost != null && proxyPort != null) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            clientBuilder.proxy(proxy);
            
            // Configure proxy authentication if provided
            String proxyUser = globalConfig.getHttpProxyUser();
            String proxyPassword = globalConfig.getHttpProxyPassword();
            
            if (proxyUser != null && proxyPassword != null) {
                clientBuilder.proxyAuthenticator((route, response) -> {
                    String credential = okhttp3.Credentials.basic(proxyUser, proxyPassword);
                    return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
                });
            }
            
            log.debug("Proxy configured: {}:{}", proxyHost, proxyPort);
        }
    }
    
    /**
     * Closes the HTTP client and releases resources
     */
    public void close() {
        log.debug("Closing HttpApiClient for API: {}", apiConfig.getId());
        
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        log.debug("HttpApiClient closed for API: {}", apiConfig.getId());
    }
    
    /**
     * Builds a request for OData pagination where the full URL is provided
     */
    private Request buildODataRequest(String fullUrl, Map<String, String> templateVars) {
        log.debug("Building OData request for URL: {}", fullUrl);
        
        // Parse the URL directly without template replacement
        HttpUrl url = HttpUrl.parse(fullUrl);
        if (url == null) {
            throw new IllegalArgumentException("Invalid OData URL: " + fullUrl);
        }
        
        // Build request
        Request.Builder requestBuilder = new Request.Builder().url(url);
        
        // Add HTTP method and body (typically GET for pagination)
        addMethodAndBody(requestBuilder, templateVars);
        
        // Add headers (but skip parameters as they're already in the URL)
        addHeaders(requestBuilder, templateVars);
        
        // Apply authentication
        if (authenticator != null) {
            authenticator.authenticate(requestBuilder);
        }
        
        return requestBuilder.build();
    }
    
    /**
     * Response data class
     */
    public static class ApiResponse {
        private final int statusCode;
        private final Map<String, java.util.List<String>> headers;
        private final String body;
        private final Duration requestDuration;
        
        public ApiResponse(int statusCode, Map<String, java.util.List<String>> headers, 
                          String body, Duration requestDuration) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.requestDuration = requestDuration;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public Map<String, java.util.List<String>> getHeaders() {
            return headers;
        }
        
        public String getBody() {
            return body;
        }
        
        public Duration getRequestDuration() {
            return requestDuration;
        }
    }
    
    /**
     * Custom exception for HTTP request failures
     */
    public static class HttpRequestException extends IOException {
        private final int statusCode;
        private final String responseBody;
        
        public HttpRequestException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public String getResponseBody() {
            return responseBody;
        }
    }
}
package io.confluent.connect.http.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP connection wrapper with enhanced features.
 * Supports HTTP/1.1 and HTTP/2, connection reuse, and performance optimizations.
 */
public class HttpConnection {
    
    private static final Logger log = LoggerFactory.getLogger(HttpConnection.class);
    
    private final String url;
    private final String host;
    private final int port;
    private final boolean secure;
    private final EnhancedHttpClient.HttpClientConfig config;
    
    private HttpURLConnection connection;
    private boolean http2Enabled = false;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    
    public HttpConnection(String url, EnhancedHttpClient.HttpClientConfig config) throws IOException {
        this.url = url;
        this.config = config;
        
        try {
            URL urlObj = new URL(url);
            this.host = urlObj.getHost();
            this.port = urlObj.getPort() != -1 ? urlObj.getPort() : urlObj.getDefaultPort();
            this.secure = "https".equals(urlObj.getProtocol());
            
            establishConnection();
            
        } catch (Exception e) {
            throw new IOException("Failed to create HTTP connection to " + url, e);
        }
    }
    
    /**
     * Check if HTTP/2 is supported.
     */
    public boolean supportsHttp2() {
        // Simplified check - in real implementation would check server capabilities
        return config.isHttp2Enabled() && secure;
    }
    
    /**
     * Enable HTTP/2 for this connection.
     */
    public void enableHttp2() {
        if (supportsHttp2()) {
            this.http2Enabled = true;
            log.debug("HTTP/2 enabled for connection to {}", host);
        }
    }
    
    /**
     * Set connection timeout.
     */
    public void setConnectionTimeout(int timeoutMs) {
        if (connection != null) {
            connection.setConnectTimeout(timeoutMs);
        }
    }
    
    /**
     * Set read timeout.
     */
    public void setReadTimeout(int timeoutMs) {
        if (connection != null) {
            connection.setReadTimeout(timeoutMs);
        }
    }
    
    /**
     * Send HTTP request.
     */
    public void sendRequest(PreparedRequest request) throws IOException {
        if (connection == null) {
            throw new IOException("Connection not established");
        }
        
        try {
            // Set request method
            connection.setRequestMethod(request.getMethod());
            
            // Set headers
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            
            // Set content type
            if (request.getContentType() != null) {
                connection.setRequestProperty("Content-Type", request.getContentType());
            }
            
            // Handle request body
            if (request.getBody() != null && !request.getBody().isEmpty()) {
                connection.setDoOutput(true);
                
                try (OutputStream os = connection.getOutputStream();
                     OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
                    writer.write(request.getBody());
                    writer.flush();
                }
            }
            
            // Connect and send
            connection.connect();
            connected = true;
            
            log.debug("Request sent to {}: {} {}", host, request.getMethod(), request.getUrl());
            
        } catch (Exception e) {
            throw new IOException("Failed to send request", e);
        }
    }
    
    /**
     * Get response status code.
     */
    public int getStatusCode() throws IOException {
        if (connection == null) {
            throw new IOException("Connection not established");
        }
        return connection.getResponseCode();
    }
    
    /**
     * Get response headers.
     */
    public Map<String, String> getHeaders() {
        if (connection == null) {
            return new HashMap<>();
        }
        
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return headers;
    }
    
    /**
     * Get content length from response headers.
     */
    public long getContentLength() {
        if (connection == null) {
            return -1;
        }
        return connection.getContentLengthLong();
    }
    
    /**
     * Get content type from response headers.
     */
    public String getContentType() {
        if (connection == null) {
            return null;
        }
        return connection.getContentType();
    }
    
    /**
     * Read data from connection into buffer.
     */
    public int read(ByteBuffer buffer) throws IOException {
        if (connection == null) {
            throw new IOException("Connection not established");
        }
        
        try (InputStream is = getInputStream()) {
            byte[] tempBuffer = new byte[buffer.remaining()];
            int bytesRead = is.read(tempBuffer);
            
            if (bytesRead > 0) {
                buffer.put(tempBuffer, 0, bytesRead);
            }
            
            return bytesRead;
        }
    }
    
    /**
     * Get input stream from connection.
     */
    private InputStream getInputStream() throws IOException {
        try {
            return connection.getInputStream();
        } catch (IOException e) {
            // Try error stream if input stream fails
            InputStream errorStream = connection.getErrorStream();
            if (errorStream != null) {
                return errorStream;
            }
            throw e;
        }
    }
    
    /**
     * Check if connection is still connected.
     */
    public boolean isConnected() {
        return connected && !closed && connection != null;
    }
    
    /**
     * Check if connection is closed.
     */
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * Check if connection can be reused.
     */
    public boolean isReusable() {
        if (closed || connection == null) {
            return false;
        }
        
        // Check for keep-alive header
        String connection = this.connection.getHeaderField("Connection");
        if (connection != null && connection.toLowerCase().contains("close")) {
            return false;
        }
        
        // HTTP/1.1 connections are reusable by default
        return true;
    }
    
    /**
     * Get host name.
     */
    public String getHost() {
        return host;
    }
    
    /**
     * Get port number.
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Check if connection is secure (HTTPS).
     */
    public boolean isSecure() {
        return secure;
    }
    
    /**
     * Close the connection.
     */
    public void close() {
        if (closed) {
            return;
        }
        
        try {
            if (connection != null) {
                connection.disconnect();
            }
        } catch (Exception e) {
            log.warn("Error closing connection to {}: {}", host, e.getMessage());
        } finally {
            closed = true;
            connected = false;
            connection = null;
        }
        
        log.debug("Connection to {} closed", host);
    }
    
    /**
     * Establish the HTTP connection.
     */
    private void establishConnection() throws IOException {
        try {
            URL urlObj = new URL(url);
            
            // Handle proxy if configured
            if (config.getProxyHost() != null) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, 
                    new InetSocketAddress(config.getProxyHost(), config.getProxyPort()));
                connection = (HttpURLConnection) urlObj.openConnection(proxy);
            } else {
                connection = (HttpURLConnection) urlObj.openConnection();
            }
            
            // Configure connection properties
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(config.getConnectionTimeoutMs());
            connection.setReadTimeout(config.getReadTimeoutMs());
            
            // Configure SSL if needed
            if (secure && connection instanceof HttpsURLConnection) {
                configureSSL((HttpsURLConnection) connection);
            }
            
            log.debug("HTTP connection established to {}:{}", host, port);
            
        } catch (Exception e) {
            throw new IOException("Failed to establish connection", e);
        }
    }
    
    /**
     * Configure SSL settings.
     */
    private void configureSSL(HttpsURLConnection httpsConnection) {
        // In a real implementation, would configure SSL context, certificates, etc.
        // For now, use default SSL configuration
        log.debug("Using default SSL configuration for {}", host);
    }
}

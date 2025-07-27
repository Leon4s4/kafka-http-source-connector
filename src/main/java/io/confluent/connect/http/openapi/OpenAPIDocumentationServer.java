package io.confluent.connect.http.openapi;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

/**
 * OpenAPI 3.0 specification generator and documentation server for HTTP Source Connector.
 * Provides comprehensive API documentation with runtime schema discovery and examples.
 */
public class OpenAPIDocumentationServer {
    
    private static final Logger log = LoggerFactory.getLogger(OpenAPIDocumentationServer.class);
    
    private final HttpSourceConnectorConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, APIEndpointInfo> discoveredEndpoints;
    
    private HttpServer server;
    private volatile boolean running = false;
    
    // OpenAPI server configuration
    private final int documentationPort;
    private final String documentationHost;
    private final String basePath;
    private final boolean enabled;
    
    public OpenAPIDocumentationServer(HttpSourceConnectorConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.discoveredEndpoints = new ConcurrentHashMap<>();
        
        // Configuration
        this.enabled = isDocumentationEnabled(config);
        this.documentationPort = getDocumentationPort(config);
        this.documentationHost = getDocumentationHost(config);
        this.basePath = getBasePath(config);
    }
    
    public void start() {
        if (!enabled) {
            log.info("OpenAPI documentation server disabled");
            return;
        }
        
        try {
            // Start HTTP server
            server = HttpServer.create(new InetSocketAddress(documentationHost, documentationPort), 0);
            
            // Register documentation endpoints
            server.createContext("/openapi.json", new OpenAPISpecHandler());
            server.createContext("/docs", new SwaggerUIHandler());
            server.createContext("/docs/", new SwaggerUIHandler());
            server.createContext("/redoc", new ReDocHandler());
            server.createContext("/api-docs", new APIDocsHandler());
            server.createContext("/schemas", new SchemasHandler());
            
            server.start();
            running = true;
            
            log.info("OpenAPI documentation server started on {}:{}", documentationHost, documentationPort);
            log.info("Swagger UI available at: http://{}:{}/docs", documentationHost, documentationPort);
            log.info("OpenAPI spec available at: http://{}:{}/openapi.json", documentationHost, documentationPort);
            
        } catch (Exception e) {
            log.error("Failed to start OpenAPI documentation server", e);
            throw new RuntimeException("Failed to start documentation server", e);
        }
    }
    
    public void stop() {
        if (server != null) {
            server.stop(1);
            running = false;
            log.info("OpenAPI documentation server stopped");
        }
    }
    
    /**
     * Register a discovered API endpoint for documentation.
     */
    public void registerEndpoint(String apiId, String url, String method, 
                                APIEndpointInfo endpointInfo) {
        if (!enabled) {
            return;
        }
        
        String key = method.toUpperCase() + ":" + url;
        discoveredEndpoints.put(key, endpointInfo);
        
        log.debug("Registered API endpoint for documentation: {} {}", method, url);
    }
    
    /**
     * Update endpoint information with runtime discoveries.
     */
    public void updateEndpointInfo(String url, String method, String responseSchema, 
                                 String exampleResponse, int statusCode) {
        if (!enabled) {
            return;
        }
        
        String key = method.toUpperCase() + ":" + url;
        APIEndpointInfo existing = discoveredEndpoints.get(key);
        
        if (existing != null) {
            existing.addResponseExample(statusCode, exampleResponse);
            existing.updateResponseSchema(responseSchema);
        } else {
            // Create new endpoint info
            APIEndpointInfo info = new APIEndpointInfo(url, method);
            info.addResponseExample(statusCode, exampleResponse);
            info.updateResponseSchema(responseSchema);
            discoveredEndpoints.put(key, info);
        }
        
        log.debug("Updated endpoint info: {} {} (status: {})", method, url, statusCode);
    }
    
    private ObjectNode generateOpenAPISpec() {
        ObjectNode spec = objectMapper.createObjectNode();
        
        // OpenAPI version and info
        spec.put("openapi", "3.0.3");
        
        ObjectNode info = objectMapper.createObjectNode();
        info.put("title", "Kafka HTTP Source Connector API");
        info.put("description", "API documentation for HTTP Source Connector endpoints");
        info.put("version", "1.0.0");
        
        ObjectNode contact = objectMapper.createObjectNode();
        contact.put("name", "Confluent");
        contact.put("url", "https://www.confluent.io");
        info.set("contact", contact);
        
        ObjectNode license = objectMapper.createObjectNode();
        license.put("name", "Apache 2.0");
        license.put("url", "https://www.apache.org/licenses/LICENSE-2.0");
        info.set("license", license);
        
        spec.set("info", info);
        
        // Servers
        ArrayNode servers = objectMapper.createArrayNode();
        ObjectNode server = objectMapper.createObjectNode();
        server.put("url", basePath);
        server.put("description", "HTTP Source Connector APIs");
        servers.add(server);
        spec.set("servers", servers);
        
        // Paths
        ObjectNode paths = objectMapper.createObjectNode();
        
        for (Map.Entry<String, APIEndpointInfo> entry : discoveredEndpoints.entrySet()) {
            String[] keyParts = entry.getKey().split(":", 2);
            String method = keyParts[0].toLowerCase();
            String path = keyParts[1];
            APIEndpointInfo endpointInfo = entry.getValue();
            
            // Normalize path for OpenAPI
            String normalizedPath = normalizePath(path);
            
            ObjectNode pathItem = (ObjectNode) paths.get(normalizedPath);
            if (pathItem == null) {
                pathItem = objectMapper.createObjectNode();
                paths.set(normalizedPath, pathItem);
            }
            
            ObjectNode operation = createOperationObject(endpointInfo);
            pathItem.set(method, operation);
        }
        
        spec.set("paths", paths);
        
        // Components (schemas, etc.)
        ObjectNode components = objectMapper.createObjectNode();
        ObjectNode schemas = generateSchemas();
        components.set("schemas", schemas);
        spec.set("components", components);
        
        // Tags
        ArrayNode tags = objectMapper.createArrayNode();
        ObjectNode httpSourceTag = objectMapper.createObjectNode();
        httpSourceTag.put("name", "HTTP Source");
        httpSourceTag.put("description", "HTTP API endpoints monitored by the connector");
        tags.add(httpSourceTag);
        spec.set("tags", tags);
        
        return spec;
    }
    
    private ObjectNode createOperationObject(APIEndpointInfo endpointInfo) {
        ObjectNode operation = objectMapper.createObjectNode();
        
        operation.put("summary", "HTTP API endpoint");
        operation.put("description", "Endpoint monitored by HTTP Source Connector");
        
        ArrayNode tags = objectMapper.createArrayNode();
        tags.add("HTTP Source");
        operation.set("tags", tags);
        
        // Parameters (if any)
        if (endpointInfo.getParameters() != null && !endpointInfo.getParameters().isEmpty()) {
            ArrayNode parameters = objectMapper.createArrayNode();
            for (APIParameter param : endpointInfo.getParameters()) {
                ObjectNode parameter = objectMapper.createObjectNode();
                parameter.put("name", param.getName());
                parameter.put("in", param.getLocation());
                parameter.put("required", param.isRequired());
                parameter.put("description", param.getDescription());
                
                ObjectNode schema = objectMapper.createObjectNode();
                schema.put("type", param.getType());
                if (param.getExample() != null) {
                    schema.put("example", param.getExample());
                }
                parameter.set("schema", schema);
                
                parameters.add(parameter);
            }
            operation.set("parameters", parameters);
        }
        
        // Responses
        ObjectNode responses = objectMapper.createObjectNode();
        
        for (Map.Entry<Integer, String> responseEntry : endpointInfo.getResponseExamples().entrySet()) {
            int statusCode = responseEntry.getKey();
            String example = responseEntry.getValue();
            
            ObjectNode response = objectMapper.createObjectNode();
            response.put("description", getStatusDescription(statusCode));
            
            ObjectNode content = objectMapper.createObjectNode();
            ObjectNode mediaType = objectMapper.createObjectNode();
            
            ObjectNode schema = objectMapper.createObjectNode();
            if (endpointInfo.getResponseSchema() != null) {
                // Use discovered schema
                schema.put("type", "object");
                schema.put("description", "Response schema discovered at runtime");
            } else {
                schema.put("type", "object");
            }
            mediaType.set("schema", schema);
            
            // Add example
            if (example != null) {
                ObjectNode examples = objectMapper.createObjectNode();
                ObjectNode exampleObj = objectMapper.createObjectNode();
                exampleObj.put("summary", "Runtime response example");
                try {
                    exampleObj.set("value", objectMapper.readTree(example));
                } catch (Exception e) {
                    exampleObj.put("value", example);
                }
                examples.set("runtime", exampleObj);
                mediaType.set("examples", examples);
            }
            
            content.set("application/json", mediaType);
            response.set("content", content);
            
            responses.set(String.valueOf(statusCode), response);
        }
        
        // Default response if no examples
        if (responses.isEmpty()) {
            ObjectNode defaultResponse = objectMapper.createObjectNode();
            defaultResponse.put("description", "Successful response");
            
            ObjectNode content = objectMapper.createObjectNode();
            ObjectNode mediaType = objectMapper.createObjectNode();
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            mediaType.set("schema", schema);
            content.set("application/json", mediaType);
            defaultResponse.set("content", content);
            
            responses.set("200", defaultResponse);
        }
        
        operation.set("responses", responses);
        
        return operation;
    }
    
    private ObjectNode generateSchemas() {
        ObjectNode schemas = objectMapper.createObjectNode();
        
        // Error schema
        ObjectNode errorSchema = objectMapper.createObjectNode();
        errorSchema.put("type", "object");
        errorSchema.put("description", "Error response");
        
        ObjectNode errorProperties = objectMapper.createObjectNode();
        
        ObjectNode errorField = objectMapper.createObjectNode();
        errorField.put("type", "string");
        errorField.put("description", "Error message");
        errorProperties.set("error", errorField);
        
        ObjectNode timestampField = objectMapper.createObjectNode();
        timestampField.put("type", "integer");
        timestampField.put("format", "int64");
        timestampField.put("description", "Error timestamp");
        errorProperties.set("timestamp", timestampField);
        
        errorSchema.set("properties", errorProperties);
        
        ArrayNode required = objectMapper.createArrayNode();
        required.add("error");
        required.add("timestamp");
        errorSchema.set("required", required);
        
        schemas.set("Error", errorSchema);
        
        return schemas;
    }
    
    private String normalizePath(String path) {
        // Convert URL templates to OpenAPI path parameters
        // This is a simple implementation - could be enhanced
        return path.replaceAll("\\{([^}]+)\\}", "{$1}");
    }
    
    private String getStatusDescription(int statusCode) {
        switch (statusCode) {
            case 200: return "Successful response";
            case 201: return "Created";
            case 400: return "Bad request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not found";
            case 429: return "Too many requests";
            case 500: return "Internal server error";
            case 502: return "Bad gateway";
            case 503: return "Service unavailable";
            default: return "HTTP response";
        }
    }
    
    // HTTP Handlers
    
    private class OpenAPISpecHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                ObjectNode spec = generateOpenAPISpec();
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                                        .writeValueAsString(spec);
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                sendResponse(exchange, 200, json);
                
            } catch (Exception e) {
                log.error("Error generating OpenAPI spec", e);
                sendResponse(exchange, 500, "{\"error\":\"Failed to generate OpenAPI spec\"}");
            }
        }
    }
    
    private class SwaggerUIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            String html = generateSwaggerUI();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            sendResponse(exchange, 200, html);
        }
    }
    
    private class ReDocHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            String html = generateReDocUI();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            sendResponse(exchange, 200, html);
        }
    }
    
    private class APIDocsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            ObjectNode response = objectMapper.createObjectNode();
            response.put("title", "HTTP Source Connector API Documentation");
            response.put("description", "Runtime discovered API endpoints");
            response.put("totalEndpoints", discoveredEndpoints.size());
            
            ArrayNode endpoints = objectMapper.createArrayNode();
            for (Map.Entry<String, APIEndpointInfo> entry : discoveredEndpoints.entrySet()) {
                String[] keyParts = entry.getKey().split(":", 2);
                ObjectNode endpoint = objectMapper.createObjectNode();
                endpoint.put("method", keyParts[0]);
                endpoint.put("path", keyParts[1]);
                endpoint.put("responseExamples", entry.getValue().getResponseExamples().size());
                endpoints.add(endpoint);
            }
            response.set("endpoints", endpoints);
            
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(response);
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            sendResponse(exchange, 200, json);
        }
    }
    
    private class SchemasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            ObjectNode schemas = generateSchemas();
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(schemas);
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            sendResponse(exchange, 200, json);
        }
    }
    
    private String generateSwaggerUI() {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "  <title>HTTP Source Connector API</title>\n" +
               "  <link rel=\"stylesheet\" type=\"text/css\" href=\"https://unpkg.com/swagger-ui-dist@4.15.5/swagger-ui.css\" />\n" +
               "</head>\n" +
               "<body>\n" +
               "  <div id=\"swagger-ui\"></div>\n" +
               "  <script src=\"https://unpkg.com/swagger-ui-dist@4.15.5/swagger-ui-bundle.js\"></script>\n" +
               "  <script>\n" +
               "    SwaggerUIBundle({\n" +
               "      url: '/openapi.json',\n" +
               "      dom_id: '#swagger-ui',\n" +
               "      presets: [\n" +
               "        SwaggerUIBundle.presets.apis,\n" +
               "        SwaggerUIBundle.presets.standalone\n" +
               "      ]\n" +
               "    });\n" +
               "  </script>\n" +
               "</body>\n" +
               "</html>";
    }
    
    private String generateReDocUI() {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "  <title>HTTP Source Connector API</title>\n" +
               "  <meta charset=\"utf-8\"/>\n" +
               "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
               "  <link href=\"https://fonts.googleapis.com/css?family=Montserrat:300,400,700|Roboto:300,400,700\" rel=\"stylesheet\">\n" +
               "  <style>\n" +
               "    body { margin: 0; padding: 0; }\n" +
               "  </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "  <redoc spec-url='/openapi.json'></redoc>\n" +
               "  <script src=\"https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js\"></script>\n" +
               "</body>\n" +
               "</html>";
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }
    
    // Configuration helpers
    private boolean isDocumentationEnabled(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("openapi.enabled", "true"));
    }
    
    private int getDocumentationPort(HttpSourceConnectorConfig config) {
        try {
            return Integer.parseInt(System.getProperty("openapi.port", "8085"));
        } catch (NumberFormatException e) {
            return 8085;
        }
    }
    
    private String getDocumentationHost(HttpSourceConnectorConfig config) {
        return System.getProperty("openapi.host", "localhost");
    }
    
    private String getBasePath(HttpSourceConnectorConfig config) {
        return System.getProperty("openapi.base.path", "http://localhost:8080");
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public int getPort() {
        return documentationPort;
    }
    
    public String getHost() {
        return documentationHost;
    }
}

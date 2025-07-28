package io.confluent.connect.http.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Shared TestContainers configuration using singleton pattern for optimal performance.
 * This base class provides reusable containers across all integration tests.
 */
@Testcontainers
public abstract class BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(BaseIntegrationTest.class);
    
    // Shared network for all containers - prevents random network names
    protected static final Network SHARED_NETWORK = Network.newNetwork();
    
    // Shared Kafka container with optimizations
    @Container
    protected static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withNetwork(SHARED_NETWORK)
            .withNetworkAliases("kafka")
            .withReuse(true)  // Enable container reuse
            .withLabel("project", "kafka-http-source-connector")  // Project-specific label
            .withLabel("version", "optimized")  // Version label for container versioning
            .waitingFor(Wait.forLogMessage(".*started \\(kafka.server.KafkaServer\\).*", 1));
    
    // Shared Schema Registry container with optimizations
    @Container
    protected static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>(
            DockerImageName.parse("confluentinc/cp-schema-registry:7.4.0"))
            .withNetwork(SHARED_NETWORK)
            .withNetworkAliases("schema-registry")
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withExposedPorts(8081)
            .withReuse(true)  // Enable container reuse
            .withLabel("project", "kafka-http-source-connector")
            .withLabel("version", "optimized")
            .dependsOn(KAFKA)
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200));
    
    // Optional Redis container for caching tests (only started when needed)
    protected static GenericContainer<?> REDIS;
    
    // Optional Vault container for secrets management tests (only started when needed)
    protected static GenericContainer<?> VAULT;
    
    /**
     * Initialize Redis container only when needed
     */
    protected static void initializeRedis() {
        if (REDIS == null || !REDIS.isRunning()) {
            REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withNetwork(SHARED_NETWORK)
                    .withNetworkAliases("redis")
                    .withExposedPorts(6379)
                    .withReuse(true)
                    .withLabel("project", "kafka-http-source-connector")
                    .withLabel("version", "optimized")
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
            REDIS.start();
        }
    }
    
    /**
     * Initialize Vault container only when needed
     */
    protected static void initializeVault() {
        if (VAULT == null || !VAULT.isRunning()) {
            VAULT = new GenericContainer<>(DockerImageName.parse("vault:1.13.3"))
                    .withNetwork(SHARED_NETWORK)
                    .withNetworkAliases("vault")
                    .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "test-token")
                    .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                    .withExposedPorts(8200)
                    .withReuse(true)
                    .withLabel("project", "kafka-http-source-connector")
                    .withLabel("version", "optimized")
                    .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));
            VAULT.start();
        }
    }
    
    /**
     * Get connection properties for tests
     */
    protected Map<String, String> getBaseTestProperties() {
        return Map.of(
            "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
            "schema.registry.url", "http://localhost:" + SCHEMA_REGISTRY.getMappedPort(8081),
            "test.environment", "optimized"
        );
    }
    
    /**
     * Log container startup times for monitoring performance improvements
     */
    protected static void logContainerInfo() {
        log.info("=== Container Information ===");
        log.info("Kafka bootstrap servers: {}", KAFKA.getBootstrapServers());
        log.info("Schema Registry URL: http://localhost:{}", SCHEMA_REGISTRY.getMappedPort(8081));
        if (REDIS != null && REDIS.isRunning()) {
            log.info("Redis URL: redis://localhost:{}", REDIS.getMappedPort(6379));
        }
        if (VAULT != null && VAULT.isRunning()) {
            log.info("Vault URL: http://localhost:{}", VAULT.getMappedPort(8200));
        }
        log.info("=== End Container Information ===");
    }
}

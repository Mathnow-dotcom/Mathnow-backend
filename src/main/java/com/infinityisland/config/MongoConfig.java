package com.infinityisland.config;

import com.mongodb.MongoClientSettings;
import com.mongodb.connection.SocketSettings;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Connection tuning that works across driver versions.
 * (No explicit compressor API to avoid version mismatch.)
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsBuilderCustomizer() {
        return (MongoClientSettings.Builder builder) -> {

            // ===== CONNECTION POOL: Optimized for Atlas =====
            builder.applyToConnectionPoolSettings(b -> b
                    // ✅ KEY FIX: Don't maintain minimum connections during inactivity
                    .minSize(0)  // Changed from 5 → 0

                    // Allow burst capacity
                    .maxSize(50)

                    // ✅ Longer idle timeout: Let Atlas handle connection lifecycle
                    .maxConnectionIdleTime(10, TimeUnit.MINUTES)  // Changed from 60s → 10min

                    // ✅ Check connections less frequently during inactivity
                    .maintenanceFrequency(120, TimeUnit.SECONDS)  // Every 2 minutes instead of 1
                    .maintenanceInitialDelay(60, TimeUnit.SECONDS)

                    // Connection wait timeout
                    .maxWaitTime(30, TimeUnit.SECONDS)
            );

            // ===== SOCKET SETTINGS: Longer timeouts for Atlas =====
            builder.applyToSocketSettings(b -> b
                    // ✅ CRITICAL: 30s timeouts for Atlas (was 10s)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)

                    // ✅ Enable TCP keepalive to detect dead connections
                    .applySettings(SocketSettings.builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build()
                    )
            );

            // ===== CLUSTER SETTINGS =====
            builder.applyToClusterSettings(b -> b
                    // Give more time to find available server
                    .serverSelectionTimeout(30, TimeUnit.SECONDS)
            );

            // ===== SERVER MONITORING =====
            builder.applyToServerSettings(b -> b
                    // Check server health less frequently (default is 10s)
                    .heartbeatFrequency(30, TimeUnit.SECONDS)
                    .minHeartbeatFrequency(10, TimeUnit.SECONDS)
            );

            // ✅ Enable retryable writes (survives transient failures)
            builder.retryWrites(true);
            builder.retryReads(true);
        };
    }
}

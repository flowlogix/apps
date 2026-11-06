/*
 * Copyright (C) 2011-2026 Flow Logix, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flowlogix.starter;

import com.flowlogix.starter.ArchetypeGenerator.Parameter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Node-local disk ZIPs indexed by normalized cURL commands.
 */
@Slf4j
@ApplicationScoped
public class ArchetypeCache {
    @Inject
    ArchetypeGenerator generator;
    @Inject
    @ConfigProperty(name = "com.flowlogix.starter.cache-hours", defaultValue = "4")
    long hours;
    @Inject
    @ConfigProperty(name = "com.flowlogix.starter.cache-max-entries", defaultValue = "1000")
    int maxEntries;
    @Inject
    @ConfigProperty(name = "com.flowlogix.starter.cache-warmup-enabled", defaultValue = "true")
    boolean warmupEnabled;
    @Resource
    ManagedExecutorService executor;

    Clock clock = Clock.systemUTC();
    @Getter
    @Inject
    @ConfigProperty(name = "com.flowlogix.starter.cache-warmup-delay-ms", defaultValue = "2000")
    private int cacheWarmupDelay;
    private final Map<String, CachedZip> entries = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<CachedZip>> pending = new HashMap<>();
    private boolean closed;

    /**
     * Per-session budget for speculative generations, independent of cached downloads.
     */
    public static class WarmupBudget implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final int GENERATIONS = 5;
        private int generations;
        private Instant resumeAt = Instant.MIN;

        private synchronized boolean acquire(Instant now) {
            if (generations == GENERATIONS) {
                if (now.isBefore(resumeAt)) {
                    return false;
                }
                generations = 0;
            }
            if (++generations == GENERATIONS) {
                resumeAt = now.plus(Duration.ofMinutes(1));
            }
            return true;
        }
    }

    public record Download(int status, String output, InputStream input) implements AutoCloseable {
        @SneakyThrows(IOException.class)
        public void write(OutputStream outputStream) {
            try (this) {
                input.transferTo(outputStream);
            }
        }

        @Override
        @SneakyThrows(IOException.class)
        public void close() {
            if (input != null) {
                input.close();
            }
        }
    }

    private record CachedZip(Path path, int status, String output, Instant created) {
        @SneakyThrows(IOException.class)
        void delete() {
            Files.deleteIfExists(path);
        }
    }

    @PostConstruct
    void start() {
        if (hours < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("Cache hours and maximum entries must be positive");
        }
    }

    public void warm(Parameter[] parameters) {
        warm(parameters, null);
    }

    public void warm(Parameter[] parameters, WarmupBudget budget) {
        if (warmupEnabled) {
            request(parameters, () -> budget == null || budget.acquire(clock.instant()));
        }
    }

    @SneakyThrows(IOException.class)
    public Download download(Parameter[] parameters) {
        while (true) {
            CachedZip zip = request(parameters, () -> true).join();
            synchronized (this) {
                if (zip.status != 0) {
                    return new Download(zip.status, zip.output, null);
                }
                if (entries.containsValue(zip)) {
                    // Open before eviction unlinks the ZIP; active streams remain valid on the local filesystem.
                    return new Download(0, "", Files.newInputStream(zip.path));
                }
            }
        }
    }

    private synchronized CompletableFuture<CachedZip> request(Parameter[] parameters, BooleanSupplier allowGeneration) {
        if (closed) {
            throw new IllegalStateException("Archetype cache is closed");
        }
        evictExpired();
        String key = ArchetypeGenerator.cacheKey(parameters);
        CachedZip existing = entries.get(key);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        var future = pending.get(key);
        if (future == null) {
            if (!allowGeneration.getAsBoolean()) {
                return CompletableFuture.completedFuture(null);
            }
            future = new CompletableFuture<>();
            pending.put(key, future);
            var result = future;
            Parameter[] snapshot = parameters == null ? null : parameters.clone();
            try {
                executor.execute(() -> generate(key, snapshot, result));
            } catch (RuntimeException e) {
                pending.remove(key);
                log.error("Unable to schedule archetype generation", e);
                throw e;
            }
        }
        return future;
    }

    private void generate(String key, Parameter[] parameters, CompletableFuture<CachedZip> future) {
        CachedZip zip = null;
        try {
            zip = generateZip(parameters);
            synchronized (this) {
                if (zip.status == 0) {
                    if (closed) {
                        zip.delete();
                    } else {
                        evictExpired();
                        while (entries.size() >= maxEntries) {
                            var oldest = entries.entrySet().iterator();
                            oldest.next().getValue().delete();
                            oldest.remove();
                        }
                        entries.put(key, zip);
                    }
                }
                pending.remove(key);
                future.complete(zip);
            }
        } catch (Exception e) {
            if (zip != null && zip.status == 0) {
                try {
                    zip.delete();
                } catch (Exception cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            synchronized (this) {
                pending.remove(key);
                future.completeExceptionally(e);
            }
            log.error("Failed to generate cached archetype ZIP", e);
        }
    }

    private CachedZip generateZip(Parameter[] parameters) throws IOException {
        Path path = null;
        try (var result = generator.generateArchetype(parameters)) {
            if (result.status() != 0) {
                log.warn("Archetype generation failed with status {}: {}", result.status(), result.output());
                return new CachedZip(null, result.status(), result.output(), clock.instant());
            }
            path = Files.createTempFile("starter-generator-cache-", ".zip");
            try (var output = Files.newOutputStream(path)) {
                ArchetypeGenerator.createZipFile(result.temporaryPath(), output);
            }
            return new CachedZip(path, 0, "", clock.instant());
        } catch (IOException | RuntimeException e) {
            if (path != null) {
                Files.deleteIfExists(path);
            }
            throw e;
        }
    }

    synchronized void evictExpired() {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            CachedZip zip = iterator.next();
            if (!clock.instant().isBefore(zip.created.plus(Duration.ofHours(hours)))) {
                zip.delete();
                iterator.remove();
            }
        }
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        entries.values().forEach(CachedZip::delete);
        entries.clear();
    }
}

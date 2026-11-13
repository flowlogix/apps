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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.Synchronized;
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
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static com.flowlogix.starter.common.StarterParameters.PACKAGING_TYPE;

/**
 * Node-local disk ZIPs indexed by normalized cURL commands.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ArchetypeCache {
    /** Projects generated at startup and kept warm, by cache key. */
    private static final Map<String, List<Parameter>> PINNED = Map.ofEntries(pinned("war"), pinned("jar"));
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

    private final Clock clock;
    private final Path cacheDirectory = Path.of(System.getProperty("java.io.tmpdir"), "starter-cache").toAbsolutePath();
    @Getter
    @Inject
    @ConfigProperty(name = "com.flowlogix.starter.cache-warmup-delay-ms", defaultValue = "2000")
    private int cacheWarmupDelay;
    private final SequencedMap<String, CachedZip> entries = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<CachedZip>> pending = new HashMap<>();
    private boolean closed;

    @SuppressWarnings("unused")
    public ArchetypeCache() {
        this(Clock.systemUTC());
    }

    /**
     * Per-session budget for speculative generations, independent of cached downloads.
     */
    public static class WarmupBudget implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final int GENERATIONS = 5;
        private int generations;
        private Instant resumeAt = Instant.MIN;

        @Synchronized
        private boolean acquire(Instant now) {
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

    public sealed interface Result {
        record Download(@NonNull InputStream input) implements Result, AutoCloseable {
            @SneakyThrows(IOException.class)
            public void write(OutputStream outputStream) {
                try (input) {
                    input.transferTo(outputStream);
                }
            }

            @Override
            @SneakyThrows(IOException.class)
            public void close() {
                input.close();
            }
        }

        record InvalidRecord(int status, String output) implements Result { }
    }

    private record CachedZip(Path path, int status, String output, Instant created) {
        @SneakyThrows(IOException.class)
        void delete() {
            Files.deleteIfExists(path);
        }
    }

    @PostConstruct
    @SneakyThrows(IOException.class)
    void checkParameters() {
        if (hours < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("Cache hours and maximum entries must be positive");
        }
        Files.createDirectories(cacheDirectory);
        log.info("Archetype ZIP cache location: {}", cacheDirectory);
    }

    /**
     * Generates the default WAR and JAR projects ahead of requests.
     */
    public void warmDefaults() {
        PINNED.values().forEach(parameters -> warm(parameters, null));
    }

    private static Map.Entry<String, List<Parameter>> pinned(String packaging) {
        var parameters = List.of(new Parameter(PACKAGING_TYPE, packaging));
        return Map.entry(ArchetypeGenerator.cacheKey(parameters), parameters);
    }

    public void warm(List<Parameter> parameters, WarmupBudget budget) {
        if (warmupEnabled) {
            getOrGenerateZip(parameters, () -> budget == null || budget.acquire(clock.instant()));
        }
    }

    public Result download(List<Parameter> parameters) {
        return requestDownload(parameters).join();
    }

    @Synchronized
    CompletableFuture<Result> requestDownload(List<Parameter> parameters) {
        // Register under the same lock used to complete generation and evict ZIPs.
        return getOrGenerateZip(parameters, () -> true).thenApply(this::openDownload);
    }

    @SneakyThrows(IOException.class)
    private Result openDownload(CachedZip zip) {
        if (zip.status != 0) {
            return new Result.InvalidRecord(zip.status, zip.output);
        }
        // Open before eviction unlinks the ZIP; active streams remain valid on the local filesystem.
        return new Result.Download(Files.newInputStream(zip.path));
    }

    @Synchronized
    private CompletableFuture<CachedZip> getOrGenerateZip(List<Parameter> parameters, BooleanSupplier allowGeneration) {
        if (closed) {
            throw new IllegalStateException("Archetype cache is closed");
        }
        evictExpired();
        String key = ArchetypeGenerator.cacheKey(parameters);
        log.debug("Requesting cached archetype ZIP for key '{}'", key);
        CachedZip existing = entries.get(key);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return scheduleGeneration(key, parameters, allowGeneration);
    }

    private CompletableFuture<CachedZip> scheduleGeneration(String key, List<Parameter> parameters,
                                                            BooleanSupplier allowGeneration) {
        var future = pending.get(key);
        if (future == null) {
            if (!allowGeneration.getAsBoolean()) {
                return CompletableFuture.completedFuture(null);
            }
            future = new CompletableFuture<>();
            pending.put(key, future);
            var result = future;
            var snapshot = List.copyOf(parameters);
            try {
                executor.execute(() -> generate(key, snapshot, result));
            } catch (RuntimeException e) {
                pending.remove(key);
                throw e;
            }
        }
        return future;
    }

    private void generate(String key, List<Parameter> parameters, CompletableFuture<CachedZip> future) {
        CachedZip zip = null;
        try {
            zip = generateZip(parameters);
            cacheGenerated(key, zip, future);
        } catch (Exception e) {
            if (zip != null && zip.status == 0) {
                try {
                    zip.delete();
                } catch (Exception cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            failGeneration(key, future, e);
            log.error("Failed to generate cached archetype ZIP", e);
        }
    }

    @Synchronized
    private void cacheGenerated(String key, CachedZip zip, CompletableFuture<CachedZip> future) {
        if (closed && zip.status == 0) {
            zip.delete();
            failGeneration(key, future, new CancellationException("Archetype cache is closed"));
            return;
        }
        if (zip.status == 0) {
            evictExpired();
            while (!entries.containsKey(key) && entries.size() >= maxEntries) {
                entries.firstEntry().getValue().delete();
                entries.pollFirstEntry();
            }
            CachedZip previous = entries.get(key);
            if (previous != null) {
                previous.delete();
            }
            entries.put(key, zip);
        }
        pending.remove(key);
        future.complete(zip);
    }

    @Synchronized
    private void failGeneration(String key, CompletableFuture<CachedZip> future, Exception failure) {
        pending.remove(key);
        future.completeExceptionally(failure);
    }

    private CachedZip generateZip(List<Parameter> parameters) throws IOException {
        Path path = null;
        try (var result = generator.generateArchetype(parameters)) {
            if (result.status() != 0) {
                if (!result.suppressed()) {
                    log.warn("Archetype generation failed with status {}: {}", result.status(), result.output());
                }
                return new CachedZip(null, result.status(), result.output(), clock.instant());
            }
            path = Files.createTempFile(cacheDirectory, "starter-generator-cache-", ".zip");
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

    @Synchronized
    void evictExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(hours));
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            CachedZip zip = entry.getValue();
            if (!zip.created.isAfter(cutoff)) {
                if (PINNED.containsKey(entry.getKey())) {
                    if (warmupEnabled && !closed) {
                        scheduleGeneration(entry.getKey(), PINNED.get(entry.getKey()), () -> true);
                    }
                } else {
                    zip.delete();
                    iterator.remove();
                }
            }
        }
    }

    @PreDestroy
    @Synchronized
    void close() {
        closed = true;
        pending.values().forEach(future ->
                future.completeExceptionally(new CancellationException("Archetype cache is closed")));
        pending.clear();
        entries.values().forEach(CachedZip::delete);
        entries.clear();
    }
}

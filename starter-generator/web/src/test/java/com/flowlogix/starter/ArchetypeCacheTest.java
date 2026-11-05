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
import com.flowlogix.starter.ArchetypeGenerator.ReturnValue;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchetypeCacheTest {
    private static final long CACHE_HOURS = 4;
    @TempDir
    Path projects;
    private final ArchetypeCache cache = new ArchetypeCache();
    private final Queue<Runnable> jobs = new ArrayDeque<>();
    private int generated;

    @BeforeEach
    void setup() {
        cache.hours = CACHE_HOURS;
        cache.maxEntries = 2;
        cache.clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        cache.generator = mock(ArchetypeGenerator.class);
        cache.executor = mock(ManagedExecutorService.class);
        doAnswer(invocation -> jobs.add(invocation.getArgument(0))).when(cache.executor).execute(any());
        when(cache.generator.generateArchetype(any())).thenAnswer(invocation -> {
            Path project = Files.createDirectory(projects.resolve("project-" + generated++));
            Files.writeString(project.resolve("pom.xml"), "project");
            return new ReturnValue(project, 0, "");
        });
    }

    @AfterEach
    void cleanup() {
        cache.close();
    }

    @Test
    void sharesWarmupAndDownloadsForNormalizedParameters() throws IOException {
        var parameters = new Parameter[] {new Parameter("artifactId", " app "), new Parameter("version", "2")};
        var reordered = new Parameter[] {new Parameter("version", "2"), new Parameter("artifactId", "app")};
        cache.warm(parameters);
        cache.warm(reordered);
        assertThat(jobs).hasSize(1);
        jobs.remove().run();
        try (var first = cache.download(parameters); var second = cache.download(reordered);
             var zip = new ZipInputStream(first.input())) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("pom.xml");
            assertThat(new String(zip.readAllBytes())).isEqualTo("project");
            assertThat(second.input().readAllBytes()).isNotEmpty();
        }
        assertThat(projects.resolve("project-0")).doesNotExist();
        verify(cache.generator).generateArchetype(any());
    }

    @Test
    void expiresAtConfiguredLifetime() {
        cache.warm(null);
        jobs.remove().run();
        cache.clock = Clock.fixed(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)).minusSeconds(1), ZoneOffset.UTC);
        cache.warm(null);
        assertThat(jobs).isEmpty();
        cache.clock = Clock.fixed(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)), ZoneOffset.UTC);
        cache.warm(null);
        assertThat(jobs).hasSize(1);
        jobs.remove().run();
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void boundsEntriesWithoutInterruptingOpenDownloads() throws IOException {
        cache.maxEntries = 1;
        cache.warm(null);
        jobs.remove().run();
        try (var download = cache.download(null)) {
            var other = new Parameter[] {new Parameter("artifactId", "other")};
            cache.warm(other);
            jobs.remove().run();
            var output = new ByteArrayOutputStream();
            download.write(output);
            assertThat(output.size()).isPositive();
            cache.warm(null);
            assertThat(jobs).hasSize(1);
            jobs.remove().run();
        }
        verify(cache.generator, times(3)).generateArchetype(any());
    }

    @Test
    void failedGenerationsAreNotCached() throws IOException {
        Path failed = Files.createDirectory(projects.resolve("failed"));
        when(cache.generator.generateArchetype(any())).thenReturn(new ReturnValue(failed, 1, "Maven failed"));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(cache.executor).execute(any());
        try (var download = cache.download(null)) {
            assertThat(download.status()).isEqualTo(1);
            assertThat(download.output()).isEqualTo("Maven failed");
            assertThat(download.input()).isNull();
        }
        assertThat(failed).doesNotExist();
        when(cache.generator.generateArchetype(any())).thenThrow(new IllegalStateException("broken"));
        assertThatThrownBy(() -> cache.download(null)).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void defaultsShareKeysAndDifferentOptionsDoNot() {
        assertThat(ArchetypeGenerator.cacheKey(null))
                .isEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {
                        new Parameter("artifactId", "starter"), new Parameter("version", " "),
                        new Parameter("groupId", "com.example"), new Parameter("baseType", "payara")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {new Parameter("useShiro", "false")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {new Parameter("archetypeVersion", "154")}));
    }
}

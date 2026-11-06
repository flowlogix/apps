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
import com.flowlogix.starter.ArchetypeCache.Result.Download;
import com.flowlogix.starter.ArchetypeCache.Result.InvalidRecord;
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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    private final Clock clock = mock(Clock.class);
    private final ArchetypeCache cache = new ArchetypeCache(clock);
    private final Queue<Runnable> jobs = new ArrayDeque<>();
    private int generated;

    @BeforeEach
    void setup() {
        cache.hours = CACHE_HOURS;
        cache.maxEntries = 2;
        cache.warmupEnabled = true;
        cache.checkParameters();
        when(clock.instant()).thenReturn(Instant.EPOCH);
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
        try (var first = assertInstanceOf(Download.class, cache.download(parameters));
             var second = assertInstanceOf(Download.class, cache.download(reordered));
             var zip = new ZipInputStream(first.input())) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("pom.xml");
            assertThat(new String(zip.readAllBytes())).isEqualTo("project");
            assertThat(second.input().readAllBytes()).isNotEmpty();
        }
        assertThat(projects.resolve("project-0")).doesNotExist();
        verify(cache.generator).generateArchetype(any());
    }

    @Test
    void pendingDownloadsOpenBeforeEviction() throws IOException {
        cache.maxEntries = 1;
        var first = cache.requestDownload(null);
        var second = cache.requestDownload(null);
        assertThat(jobs).hasSize(1);
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        jobs.remove().run();
        try (var firstDownload = assertInstanceOf(Download.class, first.join());
             var secondDownload = assertInstanceOf(Download.class, second.join())) {
            cache.warm(new Parameter[] {new Parameter("artifactId", "other")});
            jobs.remove().run();
            assertThat(firstDownload.input().readAllBytes()).isNotEmpty()
                    .isEqualTo(secondDownload.input().readAllBytes());
        }
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void shutdownCompletesDownloadsWhoseGenerationHasNotStarted() {
        var first = cache.requestDownload(null);
        var second = cache.requestDownload(null);
        assertThat(jobs).hasSize(1);
        cache.close();
        assertThat(first).isCompletedExceptionally();
        assertThat(second).isCompletedExceptionally();
        assertThatThrownBy(first::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        jobs.remove().run();
        assertThat(first).isCompletedExceptionally();
    }

    @Test
    void expiresAtConfiguredLifetime() {
        var parameters = new Parameter[] {new Parameter("artifactId", "expiring")};
        cache.warm(parameters);
        jobs.remove().run();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)).minusSeconds(1));
        cache.warm(parameters);
        assertThat(jobs).isEmpty();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)));
        cache.warm(parameters);
        assertThat(jobs).hasSize(1);
        jobs.remove().run();
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void expiredDefaultsStayAvailableUntilRefreshSucceeds() throws IOException {
        cache.maxEntries = 1;
        cache.warm(null);
        jobs.remove().run();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)));
        cache.evictExpired();
        cache.evictExpired();
        assertThat(jobs).hasSize(1);
        try (var download = assertInstanceOf(Download.class, cache.download(null))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        when(cache.generator.generateArchetype(any())).thenThrow(new IllegalStateException("Refresh failed"));
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(null))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        doAnswer(invocation -> {
            Path project = Files.createDirectory(projects.resolve("refreshed"));
            Files.writeString(project.resolve("pom.xml"), "refreshed");
            return new ReturnValue(project, 0, "");
        }).when(cache.generator).generateArchetype(any());
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(null));
             var zip = new ZipInputStream(download.input())) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("pom.xml");
            assertThat(new String(zip.readAllBytes())).isEqualTo("refreshed");
        }
        assertThat(jobs).isEmpty();
    }

    @Test
    void boundsEntriesWithoutInterruptingOpenDownloads() throws IOException {
        cache.maxEntries = 1;
        cache.warm(null);
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(null))) {
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
    void failedGenerationCanBeRetriedAndExceptionalFailureIsPropagated() throws IOException {
        Path failed = Files.createDirectory(projects.resolve("failed"));
        when(cache.generator.generateArchetype(any())).thenReturn(new ReturnValue(failed, 1, "Maven failed"));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(cache.executor).execute(any());
        assertThat(cache.download(null)).isEqualTo(new InvalidRecord(1, "Maven failed"));

        when(cache.generator.generateArchetype(any())).thenAnswer(invocation -> {
            Path project = Files.createDirectory(projects.resolve("retry"));
            Files.writeString(project.resolve("pom.xml"), "project");
            return new ReturnValue(project, 0, "");
        });
        try (var download = assertInstanceOf(Download.class, cache.download(null))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }

        when(cache.generator.generateArchetype(any())).thenThrow(new IllegalStateException("broken"));
        var differentParameters = new Parameter[] {new Parameter("artifactId", "exception")};
        assertThatThrownBy(() -> cache.download(differentParameters)).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(cache.generator, times(3)).generateArchetype(any());
    }

    @Test
    void warmupBudgetCountsOnlyNewJobsAndResumesAfterOneMinute() {
        var budget = new ArchetypeCache.WarmupBudget();
        final int limit = 5;
        cache.warm(null, budget);
        cache.warm(null, budget);
        jobs.remove().run();
        cache.warm(null, budget);
        for (int index = 1; index < limit; index++) {
            cache.warm(new Parameter[] {new Parameter("artifactId", "app-" + index)}, budget);
        }
        assertThat(jobs).hasSize(limit - 1);
        var next = new Parameter[] {new Parameter("artifactId", "next")};
        cache.warm(next, budget);
        assertThat(jobs).hasSize(limit - 1);
        cache.warm(next, new ArchetypeCache.WarmupBudget());
        assertThat(jobs).hasSize(limit);
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofMinutes(1)).minusSeconds(1));
        cache.warm(new Parameter[] {new Parameter("artifactId", "after-pause")}, budget);
        assertThat(jobs).hasSize(limit);
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofMinutes(1)));
        cache.warm(new Parameter[] {new Parameter("artifactId", "after-pause")}, budget);
        assertThat(jobs).hasSize(limit + 1);
        while (!jobs.isEmpty()) {
            jobs.remove().run();
        }
    }

    @Test
    void disablingWarmupDoesNotPreventExplicitDownloads() throws IOException {
        cache.warmupEnabled = false;
        cache.warm(null, new ArchetypeCache.WarmupBudget());
        assertThat(jobs).isEmpty();
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(cache.executor).execute(any());
        try (var download = assertInstanceOf(Download.class, cache.download(null))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        verify(cache.generator).generateArchetype(any());
    }

    @Test
    void defaultsShareKeysAndDifferentOptionsDoNot() {
        assertThat(ArchetypeGenerator.cacheKey(null))
                .isEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {
                        new Parameter("artifactId", "starter"), new Parameter("version", " "),
                        new Parameter("groupId", "com.example"), new Parameter("baseType", "payara")}))
                .isEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {
                        new Parameter("useMavenCache", "true"), new Parameter("useCodeCoverage", "true"),
                        new Parameter("useArquillianGraphene", "true")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {new Parameter("useMavenCache", "false")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {new Parameter("useShiro", "false")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {new Parameter("archetypeVersion", "154")}));
    }
}

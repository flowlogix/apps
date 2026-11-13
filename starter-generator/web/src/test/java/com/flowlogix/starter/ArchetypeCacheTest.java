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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static com.flowlogix.starter.common.StarterParameters.PACKAGING_TYPE;
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
        when(cache.generator.generateArchetype(any())).thenAnswer(_ -> {
            Path project = Files.createDirectory(projects.resolve("project-" + generated++));
            Files.writeString(project.resolve("pom.xml"), "project");
            return new ReturnValue(project, 0, false, "");
        });
    }

    @AfterEach
    void cleanup() {
        cache.close();
    }

    @Test
    void startupWarmupSharesGenerationWithDefaultDownloads() throws IOException {
        var lifecycle = new ArchetypeCacheLifecycle();
        lifecycle.cache = cache;
        lifecycle.warmDefaults();
        assertThat(jobs).hasSize(2);
        var download = cache.requestDownload(List.of());
        var jarDownload = cache.requestDownload(List.of(new Parameter(PACKAGING_TYPE, "jar")));
        assertThat(jobs).hasSize(2);
        assertThat(download).isNotDone();
        assertThat(jarDownload).isNotDone();
        jobs.remove().run();
        jobs.remove().run();
        try (var result = assertInstanceOf(Download.class, download.join());
             var jarResult = assertInstanceOf(Download.class, jarDownload.join())) {
            assertThat(result.input().readAllBytes()).isNotEmpty();
            assertThat(jarResult.input().readAllBytes()).isNotEmpty();
        }
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void sharesWarmupAndDownloadsForNormalizedParameters() throws IOException {
        var parameters = List.of(new Parameter("artifactId", " app "), new Parameter("version", "2"));
        var reordered = List.of(new Parameter("version", "2"), new Parameter("artifactId", "app"));
        cache.warm(parameters, null);
        cache.warm(reordered, null);
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
        var first = cache.requestDownload(List.of());
        var second = cache.requestDownload(List.of());
        assertThat(jobs).hasSize(1);
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        jobs.remove().run();
        try (var firstDownload = assertInstanceOf(Download.class, first.join());
             var secondDownload = assertInstanceOf(Download.class, second.join())) {
            cache.warm(List.of(new Parameter("artifactId", "other")), null);
            jobs.remove().run();
            assertThat(firstDownload.input().readAllBytes()).isNotEmpty()
                    .isEqualTo(secondDownload.input().readAllBytes());
        }
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void shutdownCompletesDownloadsWhoseGenerationHasNotStarted() {
        var first = cache.requestDownload(List.of());
        var second = cache.requestDownload(List.of());
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
        var parameters = List.of(new Parameter("artifactId", "expiring"));
        cache.warm(parameters, null);
        jobs.remove().run();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)).minusSeconds(1));
        cache.warm(parameters, null);
        assertThat(jobs).isEmpty();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)));
        cache.warm(parameters, null);
        assertThat(jobs).hasSize(1);
        jobs.remove().run();
        verify(cache.generator, times(2)).generateArchetype(any());
    }

    @Test
    void expiredDefaultsStayAvailableUntilRefreshSucceeds() throws IOException {
        cache.maxEntries = 1;
        cache.warm(List.of(), null);
        jobs.remove().run();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(CACHE_HOURS)));
        cache.evictExpired();
        cache.evictExpired();
        assertThat(jobs).hasSize(1);
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        when(cache.generator.generateArchetype(any())).thenThrow(new IllegalStateException("Refresh failed"));
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        doAnswer(_ -> {
            Path project = Files.createDirectory(projects.resolve("refreshed"));
            Files.writeString(project.resolve("pom.xml"), "refreshed");
            return new ReturnValue(project, 0, false, "");
        }).when(cache.generator).generateArchetype(any());
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()));
             var zip = new ZipInputStream(download.input())) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("pom.xml");
            assertThat(new String(zip.readAllBytes())).isEqualTo("refreshed");
        }
        assertThat(jobs).isEmpty();
    }

    @Test
    void boundsEntriesWithoutInterruptingOpenDownloads() {
        cache.maxEntries = 1;
        cache.warm(List.of(), null);
        jobs.remove().run();
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()))) {
            var other = List.of(new Parameter("artifactId", "other"));
            cache.warm(other, null);
            jobs.remove().run();
            var output = new ByteArrayOutputStream();
            download.write(output);
            assertThat(output.size()).isPositive();
            cache.warm(List.of(), null);
            assertThat(jobs).hasSize(1);
            jobs.remove().run();
        }
        verify(cache.generator, times(3)).generateArchetype(any());
    }

    @Test
    void failedGenerationCanBeRetriedAndExceptionalFailureIsPropagated() throws IOException {
        Path failed = Files.createDirectory(projects.resolve("failed"));
        when(cache.generator.generateArchetype(any())).thenReturn(new ReturnValue(failed, 1, false, "Maven failed"));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(cache.executor).execute(any());
        assertThat(cache.download(List.of())).isEqualTo(new InvalidRecord(1, "Maven failed"));

        when(cache.generator.generateArchetype(any())).thenAnswer(_ -> {
            Path project = Files.createDirectory(projects.resolve("retry"));
            Files.writeString(project.resolve("pom.xml"), "project");
            return new ReturnValue(project, 0, false, "");
        });
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }

        when(cache.generator.generateArchetype(any())).thenThrow(new IllegalStateException("broken"));
        var differentParameters = List.of(new Parameter("artifactId", "exception"));
        assertThatThrownBy(() -> cache.download(differentParameters)).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(cache.generator, times(3)).generateArchetype(any());
    }

    @Test
    void warmupBudgetCountsOnlyNewJobsAndResumesAfterOneMinute() {
        var budget = new ArchetypeCache.WarmupBudget();
        final int limit = 5;
        cache.warm(List.of(), budget);
        cache.warm(List.of(), budget);
        jobs.remove().run();
        cache.warm(List.of(), budget);
        for (int index = 1; index < limit; index++) {
            cache.warm(List.of(new Parameter("artifactId", "app-" + index)), budget);
        }
        assertThat(jobs).hasSize(limit - 1);
        var next = List.of(new Parameter("artifactId", "next"));
        cache.warm(next, budget);
        assertThat(jobs).hasSize(limit - 1);
        cache.warm(next, new ArchetypeCache.WarmupBudget());
        assertThat(jobs).hasSize(limit);
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofMinutes(1)).minusSeconds(1));
        cache.warm(List.of(new Parameter("artifactId", "after-pause")), budget);
        assertThat(jobs).hasSize(limit);
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofMinutes(1)));
        cache.warm(List.of(new Parameter("artifactId", "after-pause")), budget);
        assertThat(jobs).hasSize(limit + 1);
        while (!jobs.isEmpty()) {
            jobs.remove().run();
        }
    }

    @Test
    void disablingWarmupDoesNotPreventExplicitDownloads() throws IOException {
        cache.warmupEnabled = false;
        var lifecycle = new ArchetypeCacheLifecycle();
        lifecycle.cache = cache;
        lifecycle.warmDefaults();
        cache.warm(List.of(), new ArchetypeCache.WarmupBudget());
        assertThat(jobs).isEmpty();
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(cache.executor).execute(any());
        try (var download = assertInstanceOf(Download.class, cache.download(List.of()))) {
            assertThat(download.input().readAllBytes()).isNotEmpty();
        }
        verify(cache.generator).generateArchetype(any());
    }
}

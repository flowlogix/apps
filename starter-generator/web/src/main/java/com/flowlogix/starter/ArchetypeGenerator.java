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

import com.flowlogix.starter.common.DownloadRequest;
import com.flowlogix.starter.common.ArchetypeMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_GROUP;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_PACKAGE;
import static com.flowlogix.starter.common.StarterParameters.DEFAULTS;
import static com.flowlogix.starter.common.StarterParameters.defaultValue;
import static com.flowlogix.util.Streams.readString;

@Slf4j
@ApplicationScoped
public class ArchetypeGenerator {
    private final Semaphore semaphore;
    private final String jvmOptions;

    public record Parameter(@NonNull String key, String value) { }
    public record ReturnValue(Path temporaryPath, int status, boolean suppressed, String output) implements AutoCloseable {
        @Override
        public void close() {
            cleanup(temporaryPath);
        }
    }

    @SuppressWarnings("unused")
    public ArchetypeGenerator() {
        this(1, "-Xmx256m");
    }

    @Inject
    public ArchetypeGenerator(@ConfigProperty(name = "com.flowlogix.starter.generator-threads", defaultValue = "4")
                              int generatorThreads,
                              @ConfigProperty(name = "com.flowlogix.starter.jvm-options", defaultValue = "-Xmx256m")
                              String jvmOptions) {
        log.debug("Generator threads: {}", generatorThreads);
        semaphore = new Semaphore(generatorThreads);
        this.jvmOptions = jvmOptions;
    }

    @SneakyThrows({IOException.class, InterruptedException.class})
    public ReturnValue generateArchetype(List<Parameter> inputParameters) {
        log.debug("Available Permits: {}", semaphore.availablePermits());
        semaphore.acquire();
        Optional<Path> rootMavenPath = Optional.empty();
        try {
            Path temporaryPath = getTemporaryPath();
            rootMavenPath = Optional.of(temporaryPath.resolve(".mvn"));
            Files.writeString(rootMavenPath.get().resolve("jvm.config"),
                    jvmOptions + System.lineSeparator());
            String projectDirectory = temporaryPath.toString();
            List<String> options = generateMavenCommandLine(inputParameters, projectDirectory);
            log.debug("Options: {}", options);
            var builder = new ProcessBuilder().command(options).directory(temporaryPath.toFile());
            String userHome = System.getProperty("user.home");
            Path java25VersionPath = Path.of(userHome).resolve("var")
                    .resolve(System.getProperty("com.flowlogix.starter.java-version-file", "java-25-version"));
            if (java25VersionPath.toFile().exists()) {
                builder.environment().put("JAVA_HOME", "%s/.sdkman/candidates/java/%s"
                        .formatted(userHome, Files.readString(java25VersionPath).strip()));
            }
            try {
                var mavenProcess = builder.start();
                return new ReturnValue(temporaryPath, mavenProcess.waitFor(), false,
                        readString(mavenProcess.getInputStream()));
            } catch (IOException | IllegalStateException e) {
                log.debug("Failed to execute Maven process", e);
                return new ReturnValue(temporaryPath, -1, e instanceof IllegalStateException, e.getMessage());
            }
        } finally {
            rootMavenPath.ifPresent(ArchetypeGenerator::cleanup);
            semaphore.release();
        }
    }

    public static List<String> generateMavenCommandLine(List<Parameter> inputParameters, String projectDirectory) {
        return Stream.concat(Stream.of("mvn", "archetype:generate"),
                extractParameters(inputParameters, projectDirectory).entrySet().stream()
                        .map(entry -> (projectDirectory != null ? "-D%s=%s" : "-D%s=\"%s\"")
                        .formatted(entry.getKey(), entry.getValue()))).toList();
    }

    static String cacheKey(List<Parameter> inputParameters) {
        var parameters = new TreeMap<>(extractParameters(inputParameters, null));
        var defaults = defaults();
        ArchetypeMetadata.properties().forEach(property -> parameters.remove(property.key(), defaults.get(property.key())));
        return DownloadRequest.curlCommand(DownloadRequest.url(DownloadRequest.endpoint(""), parameters), DEFAULT_ARTIFACT);
    }

    public static Map<String, String> defaults() {
        var defaults = new TreeMap<String, String>();
        ArchetypeMetadata.properties().stream()
                .map(property -> new Parameter(property.key(), defaultValue(property)))
                .filter(parameter -> Objects.nonNull(parameter.value()))
                .forEach(parameter -> defaults.put(parameter.key(), parameter.value()));
        defaults.putAll(defaultOverrides());
        return defaults;
    }

    private static Map<String, String> defaultOverrides() {
        var defaults = new TreeMap<>(DEFAULTS);
        String shiroVersion = System.getProperty("shiroVersion", "").strip();
        if (!shiroVersion.isBlank()) {
            defaults.put("shiroVersion", shiroVersion);
        }
        return defaults;
    }

    @SneakyThrows(IOException.class)
    private static void cleanup(Path path) {
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ArchetypeGenerator::deleteFile);
        } catch (IllegalStateException e) {
            log.debug("Failed to cleanup path: {}", path, e);
        }
    }

    private static Map<String, String> extractParameters(List<Parameter> inputParameters, String projectDirectory) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("archetypeGroupId", "com.flowlogix.archetypes");
        parameters.put("archetypeArtifactId", "starter");
        parameters.put("archetypeVersion", "LATEST");
        parameters.put("interactiveMode", "false");
        if (projectDirectory != null) {
            parameters.put("maven.multiModuleProjectDirectory", projectDirectory);
            parameters.put("outputDirectory", projectDirectory);
        }
        parameters.put("groupId", DEFAULT_GROUP);
        parameters.put("artifactId", DEFAULT_ARTIFACT);
        parameters.put("package", DEFAULT_PACKAGE);
        // same defaults as the web page, so a bare request and the page produce the same project
        parameters.putAll(defaultOverrides());

        for (Parameter parameter : inputParameters) {
            if (parameter.value() != null && !parameter.value().isBlank()) {
                parameters.put(parameter.key(), parameter.value().trim());
            }
        }
        return parameters;
    }

    private Path getTemporaryPath() throws IOException {
        Path path =  Files.createTempDirectory("starter-generator-project-");
        if (!path.resolve(".mvn").toFile().mkdirs()) {
            throw new IOException("Unable to create directory");
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        log.debug("Created temporary project directory: {}", path);
        return path;
    }

    @SneakyThrows(IOException.class)
    private static void deleteFile(Path path) {
        Files.delete(path);
    }

    static void createZipFile(Path sourceDirPath, OutputStream outputStream) throws IOException {
        try (var zipOutputStream = new ZipArchiveOutputStream(outputStream);
             var sourceDirPaths = Files.walk(sourceDirPath)) {
            sourceDirPaths.forEach(path -> addZipEntry(sourceDirPath, path, zipOutputStream));
        }
    }

    @SneakyThrows(IOException.class)
    @SuppressWarnings({"checkstyle:IllegalTokenText", "checkstyle:MagicNumber"})
    private static void addZipEntry(Path sourceDirPath, Path path, ZipArchiveOutputStream zipOutputStream) {
        String relativizedPath = sourceDirPath.relativize(path).toString();
        if (relativizedPath.isBlank()) {
            return;
        }
        boolean isDirectory = Files.isDirectory(path);
        ZipArchiveEntry zipEntry = isDirectory
                ? new ZipArchiveEntry(relativizedPath + "/")
                : new ZipArchiveEntry(relativizedPath);
        if (!isDirectory && Files.isExecutable(path)) {
            zipEntry.setUnixMode(0755);
        }
        zipOutputStream.putArchiveEntry(zipEntry);
        if (!isDirectory) {
            Files.copy(path, zipOutputStream);
        }
        zipOutputStream.closeArchiveEntry();
    }
}

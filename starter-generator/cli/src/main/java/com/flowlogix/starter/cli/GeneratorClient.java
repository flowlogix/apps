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
package com.flowlogix.starter.cli;

import com.flowlogix.starter.common.DownloadRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Client of the starter generator's download endpoint, the one behind start.flowlogix.com:
 * it requests a project and unpacks the archive that comes back.
 */
class GeneratorClient {
    /** Far more than a generated project takes: bounds what an archive may write to disk. */
    static final long MAX_PROJECT_BYTES = 16L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The generator runs Maven for every request, which takes several seconds. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BUFFER_SIZE = 8192;
    /** A streamed archive does not say which entries are executable, so the one script is named here. */
    private static final String MAVEN_WRAPPER = "mvnw";
    private static final Set<PosixFilePermission> EXECUTE = Set.of(PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);

    private final URI generatorUrl;

    GeneratorClient(URI generatorUrl) {
        this.generatorUrl = generatorUrl;
    }

    /** A failed generation, with a message fit to show the user. */
    static class GeneratorException extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean rejected;

        GeneratorException(String message, boolean rejected) {
            super(message);
            this.rejected = rejected;
        }

        /** Whether the generator refused the parameters, rather than failing on its own. */
        boolean rejected() {
            return rejected;
        }
    }

    /** Requests the project and unpacks its directory, which the generator names after the artifact id. */
    void generate(URI request, String artifactId, Path projectDir) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = download(request);
        } catch (IOException e) {
            throw new GeneratorException("Could not reach the generator at %s: %s"
                    .formatted(generatorUrl, describe(e)), false);
        }
        try (InputStream archive = response.body()) {
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw failure(response);
            }
            try {
                unpack(archive, artifactId, projectDir);
            } catch (IOException e) {
                throw new GeneratorException("Could not unpack the generated project: " + describe(e), false);
            }
        }
    }

    private static HttpResponse<InputStream> download(URI request) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return client.send(HttpRequest.newBuilder(request).timeout(REQUEST_TIMEOUT)
                .header("Accept", DownloadRequest.ARCHIVE_TYPE).build(), BodyHandlers.ofInputStream());
    }

    private static GeneratorException failure(HttpResponse<InputStream> response) throws IOException {
        boolean rejected = response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST;
        String message = (rejected ? "The generator rejected the request (HTTP %d)."
                : "The generator failed (HTTP %d).").formatted(response.statusCode());
        // Maven's output arrives as plain text, but a rejected parameter comes back as
        // the server's HTML error page, which does not carry the reason
        if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain")) {
            message += System.lineSeparator()
                    + new String(response.body().readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        return new GeneratorException(message, rejected);
    }

    /** Unpacks the project as it arrives, so the archive is never held in a file or in memory. */
    private static void unpack(InputStream archive, String artifactId, Path projectDir) throws IOException {
        Path root = projectDir.toAbsolutePath().normalize();
        createNew(projectDir);
        try (var zip = new ZipInputStream(archive)) {
            if (!extract(zip, artifactId + "/", root)) {
                throw new IOException("the archive has no '%s' directory".formatted(artifactId));
            }
            Path wrapper = root.resolve(MAVEN_WRAPPER);
            if (Files.exists(wrapper)) {
                makeExecutable(wrapper);
            }
        } catch (IOException e) {
            deleteQuietly(root);
            throw e;
        }
    }

    /** Writes the entries of one directory of the archive, and tells whether it had any. */
    private static boolean extract(ZipInputStream zip, String directory, Path root) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long unpacked = 0;
        boolean found = false;
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (!entry.getName().startsWith(directory)) {
                continue;
            }
            found = true;
            Path target = inside(root, entry.getName().substring(directory.length()));
            try (OutputStream file = create(entry, target)) {
                for (int length = zip.read(buffer); length != -1; length = zip.read(buffer)) {
                    unpacked += length;
                    if (unpacked > MAX_PROJECT_BYTES) {
                        throw new IOException("the archive is too large to be a generated project");
                    }
                    file.write(buffer, 0, length);
                }
            }
        }
        return found;
    }

    /** Opens the file an entry goes to; a directory is created instead, and has nothing to write. */
    private static OutputStream create(ZipEntry entry, Path target) throws IOException {
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return OutputStream.nullOutputStream();
        }
        Files.createDirectories(target.getParent());
        return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
    }

    /** An entry must not leave the project directory through its name. */
    private static Path inside(Path root, String name) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("the archive has an entry outside the project: '%s'".formatted(name));
        }
        return target;
    }

    /** Does nothing where files have no permissions to set, as on Windows. */
    private static void makeExecutable(Path file) throws IOException {
        var attributes = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (attributes != null) {
            Set<PosixFilePermission> permissions = attributes.readAttributes().permissions();
            permissions.addAll(EXECUTE);
            attributes.setPermissions(permissions);
        }
    }

    /** Refuses a directory that is already there, so that a failed unpack deletes only what it wrote. */
    private static void createNew(Path projectDir) throws IOException {
        Files.createDirectories(projectDir.toAbsolutePath().getParent());
        try {
            Files.createDirectory(projectDir);
        } catch (FileAlreadyExistsException _) {
            throw new IOException("'%s' already exists".formatted(projectDir));
        }
    }

    /** Best effort: a leftover file must not turn a generated project into a failure. */
    private static void deleteQuietly(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException | UncheckedIOException _) {
            // whatever is left stays where it is
        }
    }

    private static String describe(IOException e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }
}

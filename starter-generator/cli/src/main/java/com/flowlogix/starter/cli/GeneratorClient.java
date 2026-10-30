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

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.examples.Expander;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

/**
 * Client of the starter generator's download endpoint: it requests a project and unpacks the archive that comes back.
 */
@RequiredArgsConstructor
class GeneratorClient {
    // a streamed archive does not say which entries are executable
    private static final String MAVEN_WRAPPER = "mvnw";

    private final URI generatorUrl;

    /**
     * A failed generation, with a message fit to show the user.
     */
    @Getter
    static class GeneratorException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;
        /** Whether the generator refused the parameters, rather than failing on its own. */
        private final boolean rejected;

        GeneratorException(String message, boolean rejected) {
            super(message);
            this.rejected = rejected;
        }
    }

    /**
     * Requests the project and unpacks it into its directory, which the generator names after the artifact.
     */
    void generate(Map<String, String> parameters, Path projectDir) throws GeneratorException {
        var generator = QuarkusRestClientBuilder.newBuilder().baseUri(generatorUrl)
                .disableDefaultMapper(true).build(GeneratorApi.class);
        try (Response response = generator.download(parameters)) {
            if (response.getStatus() != Status.OK.getStatusCode()) {
                throw failure(response);
            }
            unpack(response.readEntity(InputStream.class), projectDir);
        } catch (ProcessingException e) {
            throw new GeneratorException("Could not reach the generator at %s: %s"
                    .formatted(generatorUrl, ExceptionUtils.getRootCauseMessage(e)), false);
        } catch (IOException e) {
            FileUtils.deleteQuietly(projectDir.toFile());
            throw new GeneratorException("Could not unpack the generated project: " + e.getMessage(), false);
        }
    }

    private static GeneratorException failure(Response response) {
        boolean rejected = response.getStatus() == Status.BAD_REQUEST.getStatusCode();
        String message = "The generator %s (HTTP %d).".formatted(rejected ? "rejected the request" : "failed",
                response.getStatus());
        // the reasons for a rejection and Maven's output come as plain text, other errors as an error page
        return new GeneratorException(MediaType.TEXT_PLAIN_TYPE.isCompatible(response.getMediaType())
                ? message + System.lineSeparator() + response.readEntity(String.class).strip() : message, rejected);
    }

    private static void unpack(InputStream archive, Path projectDir) throws IOException {
        try (var zip = new ZipArchiveInputStream(archive)) {
            new Expander().expand(zip, projectDir.getParent());
        }
        Path wrapper = projectDir.resolve(MAVEN_WRAPPER);
        if (PathUtils.isPosix(wrapper)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(wrapper);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(wrapper, permissions);
        }
    }
}

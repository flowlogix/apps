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

import com.flowlogix.starter.common.ArchetypeMetadata;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusMainTest
class StarterCliIT {
    private static final String ARCHIVE_TYPE = "application/octet-stream";

    private static LaunchResult generate(QuarkusMainLauncher launcher, URI generatorUrl, Path dir, String... options) {
        return launcher.launch(Stream.concat(Stream.of("-g", "com.acme", "-a", "app", "-o", dir.toString(),
                "--generator-url", generatorUrl.toString()), Stream.of(options)).toArray(String[]::new));
    }

    @Test
    void createsTheProjectFromTheOptionsGiven(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        try (var generator = new GeneratorStub(HTTP_OK, ARCHIVE_TYPE, GeneratorStub.archive("app/pom.xml", "app/mvnw"))) {
            LaunchResult result = generate(launcher, generator.url(), dir.resolve("out"), "--baseType", "glassfish",
                    "--useShiro");

            assertThat(result.exitCode()).as(result.getErrorOutput()).isZero();
            assertThat(generator.getRequests()).containsExactly(
                    "/sg/download/;group=com.acme;artifact=app;baseType=glassfish;useShiro=true;package=com.acme");
            assertThat(dir.resolve("out/app/pom.xml")).hasContent("app/pom.xml");
            assertThat(dir.resolve("out/app/mvnw")).isExecutable();
            assertThat(result.getOutput()).contains("Created ");
        }
    }

    @Test
    void offersEveryArchetypePropertyAsAnOption(QuarkusMainLauncher launcher) {
        String help = launcher.launch("--help").getOutput();

        assertThat(ArchetypeMetadata.properties()).isNotEmpty().allSatisfy(property ->
                assertThat(help).contains("--" + GeneratorOptions.optionName(property.key())));
    }

    @Test
    void printsTheRequestAsACurlCommand(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("-g", "com.acme", "-a", "app", "-o", dir.toString(), "--dry-run");

        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("curl -X GET -H \"Accept: application/octet-stream\" -o \"app.zip\" "
                + "\"https://start.flowlogix.com/sg/download/;group=com.acme;artifact=app;package=com.acme\"");
    }

    @Test
    void showsWhyTheGeneratorRejectedTheRequest(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        byte[] reason = "Invalid baseType 'foo': must match base|infra|payara|glassfish".getBytes(StandardCharsets.UTF_8);
        try (var generator = new GeneratorStub(HTTP_BAD_REQUEST, "text/plain", reason)) {
            LaunchResult result = generate(launcher, generator.url(), dir, "--baseType", "foo");

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.getErrorOutput()).contains("The generator rejected the request (HTTP 400).",
                    "Invalid baseType 'foo'");
        }
    }

    @Test
    void showsWhyTheGeneratorFailed(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        byte[] output = "BUILD FAILURE".getBytes(StandardCharsets.UTF_8);
        try (var generator = new GeneratorStub(HTTP_INTERNAL_ERROR, "text/plain", output)) {
            LaunchResult result = generate(launcher, generator.url(), dir);

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.getErrorOutput()).contains("The generator failed (HTTP 500).", "BUILD FAILURE");
            assertThat(dir.resolve("app")).doesNotExist();
        }
    }

    @Test
    void failsWhenTheGeneratorCannotBeReached(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        URI url;
        try (var generator = new GeneratorStub(HTTP_OK, ARCHIVE_TYPE, new byte[0])) {
            url = generator.url();
        }
        LaunchResult result = generate(launcher, url, dir);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.getErrorOutput()).contains("Could not reach the generator at " + url);
    }

    @Test
    void refusesAnExistingProjectDirectory(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("app"));
        try (var generator = new GeneratorStub(HTTP_OK, ARCHIVE_TYPE, GeneratorStub.archive("app/pom.xml"))) {
            LaunchResult result = generate(launcher, generator.url(), dir);

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.getErrorOutput()).contains("already exists");
            assertThat(generator.getRequests()).isEmpty();
        }
    }

    @Test
    void refusesAnEntryOutsideTheOutputDirectory(QuarkusMainLauncher launcher, @TempDir Path dir) throws IOException {
        byte[] archive = GeneratorStub.archive("app/pom.xml", "../escaped.txt");
        try (var generator = new GeneratorStub(HTTP_OK, ARCHIVE_TYPE, archive)) {
            LaunchResult result = generate(launcher, generator.url(), dir.resolve("out"));

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.getErrorOutput()).contains("Could not unpack the generated project");
            assertThat(dir.resolve("escaped.txt")).doesNotExist();
            assertThat(dir.resolve("out/app")).doesNotExist();
        }
    }

    @Test
    void requiresTheGroupAndTheArtifact(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--dry-run");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.getErrorOutput()).contains("Missing required options");
    }
}

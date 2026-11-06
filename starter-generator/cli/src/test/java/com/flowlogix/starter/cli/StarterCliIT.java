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
import com.flowlogix.starter.common.DownloadRequest;
import com.flowlogix.starter.common.StarterParameters;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusMainTest
class StarterCliIT {
    private static final String ARCHIVE_TYPE = "application/octet-stream";

    /**
     * The matrix parameters of a request, from its path or from a curl command, by name: what the generator
     * sees, independent of the parameters' order and of the generator's address.
     */
    private static Map<String, String> parameters(String request) {
        String path = request.contains("\"http") ? request.substring(request.indexOf("\"http") + 1,
                request.indexOf('"', request.indexOf("\"http") + 1)) : request;
        return Stream.of(path.substring(path.lastIndexOf('/') + 1).split(";")).filter(not(String::isEmpty))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(parameter -> parameter[0],
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
    }

    /**
     * The parameters the CLI sends for the given group and artifact, leaving archetype defaults to the server.
     */
    private static Map<String, String> expectedParameters(String group, String artifact) {
        var expected = new HashMap<String, String>();
        expected.put(StarterParameters.GROUP, group);
        expected.put(StarterParameters.ARTIFACT, artifact);
        expected.put(StarterParameters.PACKAGE, StarterParameters.DEFAULT_PACKAGE);
        return expected;
    }

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
            var expected = expectedParameters("com.acme", "app");
            expected.put("baseType", "glassfish");
            expected.put("useShiro", "true");
            assertThat(generator.getRequests()).hasSize(1).first().extracting(StarterCliIT::parameters)
                    .asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(expected);
            assertThat(generator.getRequests().getFirst()).startsWith("/sg/api/download/;");
            assertThat(dir.resolve("out/app/pom.xml")).hasContent("app/pom.xml");
            assertThat(dir.resolve("out/app/mvnw")).isExecutable();
            assertThat(result.getOutput()).contains("Created ");
        }
    }

    @Test
    void helpUsesDefaultsFromSelectedServer(QuarkusMainLauncher launcher) throws IOException {
        byte[] defaults = "packagingType=ear\nuseShiro=false\nshiroVersion=server-shiro\n"
                .getBytes(StandardCharsets.UTF_8);
        try (var generator = new GeneratorStub(HTTP_OK, "text/plain", defaults)) {
            LaunchResult result = launcher.launch("--generator-url", generator.url().toString(), "--help");
            String help = result.getOutput();

            assertThat(result.exitCode()).as(result.getErrorOutput()).isZero();
            assertThat(ArchetypeMetadata.properties()).isNotEmpty().allSatisfy(property ->
                    assertThat(help).contains("--" + GeneratorOptions.optionName(property.key())));
            assertThat(help).containsPattern("--packagingType=<value>\\s+default: ear")
                    .containsPattern("--useShiro\\[=<true\\|false>]\\s+default: false")
                    .containsPattern("--shiroVersion=<value>\\s+default: server-shiro")
                    .contains("Base Java package (default: " + StarterParameters.DEFAULT_PACKAGE + ")")
                    .doesNotContain("default: war", "default: true",
                            "default: " + StarterParameters.defaultValue("shiroVersion"));
            assertThat(generator.getRequests()).containsExactly("/sg/api/defaults");
        }
    }

    @Test
    void helpOmitsUnavailableDefaultsButKeepsOptionsAndValidation(QuarkusMainLauncher launcher) throws IOException {
        try (var generator = new GeneratorStub(HTTP_INTERNAL_ERROR, "text/plain", new byte[0])) {
            LaunchResult result = launcher.launch("--help", "--generator-url", generator.url().toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.getOutput()).contains("--shiroVersion=<value>", "--useShiro[=<true|false>]",
                            "must match base|infra|payara|glassfish")
                    .doesNotContain("default: war", "default: true",
                            "default: " + StarterParameters.defaultValue("shiroVersion"));
            assertThat(result.getErrorOutput()).contains("Warning: server defaults unavailable", "defaults omitted");
        }
    }

    @Test
    void helpDoesNotShowLatestDefaultsForAnotherArchetypeVersion(QuarkusMainLauncher launcher) throws IOException {
        try (var generator = new GeneratorStub(HTTP_OK, "text/plain", new byte[0])) {
            LaunchResult result = launcher.launch("--help", "--generator-url", generator.url().toString(),
                    "--archetypeVersion", "157");

            assertThat(result.exitCode()).isZero();
            assertThat(result.getErrorOutput()).contains("defaults omitted for an explicitly selected archetype version");
            assertThat(generator.getRequests()).isEmpty();
        }
    }

    @Test
    void printsTheRequestAsACurlCommand(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("-g", "com.acme", "-a", "app", "-o", dir.toString(), "--dry-run");

        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("curl -X GET -H \"Accept: application/octet-stream\" -o \"app.zip\" \"");
        assertThat(parameters(result.getOutput())).containsExactlyInAnyOrderEntriesOf(expectedParameters("com.acme", "app"));
    }

    @Test
    void preservesAnExplicitPackage(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("-o", dir.toString(), "--package", "com.acme.custom", "--dry-run");

        assertThat(result.exitCode()).as(result.getErrorOutput()).isZero();
        var expected = expectedParameters(StarterParameters.DEFAULT_GROUP, StarterParameters.DEFAULT_ARTIFACT);
        expected.put(StarterParameters.PACKAGE, "com.acme.custom");
        assertThat(parameters(result.getOutput())).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void appendsDownloadPathToCustomBaseUrl(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("-o", dir.toString(), "--generator-url",
                "https://example.com/custom", "--dry-run");

        assertThat(result.exitCode()).as(result.getErrorOutput()).isZero();
        assertThat(result.getOutput()).contains("\"https://example.com/custom/api/download/;");
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
        assertThat(result.getErrorOutput()).contains("Could not reach the generator at "
            + DownloadRequest.apiEndpoint(url.toString()));
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
    void usesTheGeneratorsDefaultGroupAndArtifact(QuarkusMainLauncher launcher, @TempDir Path dir) {
        LaunchResult result = launcher.launch("-o", dir.toString(), "--dry-run");

        assertThat(result.exitCode()).as(result.getErrorOutput()).isZero();
        assertThat(result.getOutput()).contains("-o \"starter.zip\" ");
        assertThat(parameters(result.getOutput())).containsExactlyInAnyOrderEntriesOf(
                expectedParameters(StarterParameters.DEFAULT_GROUP, StarterParameters.DEFAULT_ARTIFACT));
    }
}

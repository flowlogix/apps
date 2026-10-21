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

import com.flowlogix.test.AppServerLifecycle;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

@RunAsClient
@AppServerLifecycle
class DownloadResourceIT {
    private static final int MAX_ARTIFACT_LENGTH = 100;

    @ArquillianResource
    private URL baseURL;

    @Test
    void download() throws IOException, InterruptedException {
        // artifactId and outputDirectory are not on the allowlist and have to be ignored,
        // so is the repeated artifact, which would otherwise bypass validation
        var response = get("artifact=my-app;group=com.acme;projectName=My%20App;version=2.0;packagingType=war"
                + ";useShiro=false;artifactId=hacked;outputDirectory=hacked;artifact=..%2Fhacked");
        assertThat(response.statusCode()).withFailMessage(() -> new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(OK.getStatusCode());
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"my-app.zip\"");

        Map<String, String> entries = unzip(response.body());
        assertThat(entries.keySet()).isNotEmpty().allMatch(name -> name.startsWith("my-app/"))
                .contains("my-app/pom.xml", "my-app/mvnw", "my-app/src/test/resources/arquillian.xml")
                .doesNotContain("my-app/src/main/webapp/WEB-INF/shiro.ini")
                .noneMatch(name -> name.contains(".mvn/jvm.config"));
        assertThat(entries.get("my-app/pom.xml")).contains("<groupId>com.acme</groupId>",
                        "<artifactId>my-app</artifactId>", "<version>2.0</version>", "<name>My App</name>",
                        "<packaging>war</packaging>", "<artifactId>payara-pom</artifactId>")
                .doesNotContain("shiro", "hacked");
    }

    @Test
    void invalidArtifactRejected() throws IOException, InterruptedException {
        assertThat(get("artifact=..%2Fevil").statusCode()).isEqualTo(BAD_REQUEST.getStatusCode());
        assertThat(get("artifact=my.app").statusCode()).isEqualTo(BAD_REQUEST.getStatusCode());
        assertThat(get("artifact=" + "a".repeat(MAX_ARTIFACT_LENGTH + 1)).statusCode())
                .isEqualTo(BAD_REQUEST.getStatusCode());
    }

    private HttpResponse<byte[]> get(String matrixParameters) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("%sdownload/;%s".formatted(baseURL, matrixParameters)))
                    .header("Accept", "application/octet-stream").build(), BodyHandlers.ofByteArray());
        }
    }

    private static Map<String, String> unzip(byte[] zipFile) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var zip = new ZipArchiveInputStream(new ByteArrayInputStream(zipFile))) {
            for (ZipArchiveEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}

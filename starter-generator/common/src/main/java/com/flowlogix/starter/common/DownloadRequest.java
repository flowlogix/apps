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
package com.flowlogix.starter.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A request to the generator's download endpoint, which takes its parameters as matrix parameters of the path.
 */
public final class DownloadRequest {
    /** Media type of the project archive, for the request to accept. */
    public static final String ARCHIVE_TYPE = "application/octet-stream";

    private DownloadRequest() {
    }

    /**
     * Builds the request URL. Blank parameters are left out, so the generator applies its own defaults to them.
     */
    public static String url(String endpoint, Map<String, String> parameters) {
        return (endpoint.endsWith("/") ? endpoint : endpoint + "/") + parameters.entrySet().stream()
                .filter(parameter -> parameter.getValue() != null && !parameter.getValue().isBlank())
                .map(parameter -> ";%s=%s".formatted(parameter.getKey(), encode(parameter.getValue().trim())))
                .collect(Collectors.joining());
    }

    /**
     * The request as a curl command, which saves the archive under the artifact id.
     */
    public static String curlCommand(String url, String artifactId) {
        return "curl -X GET -H \"Accept: %s\" -o \"%s.zip\" \"%s\"".formatted(ARCHIVE_TYPE, artifactId, url);
    }

    /** Percent-encodes a matrix parameter value; in a path, '+' would not mean a space. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadRequestTest {
    @Test
    void putsTheParametersInThePath() {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("group", "com.acme");
        parameters.put("artifact", "my-app");

        assertEquals("https://example.com/sg/download/;group=com.acme;artifact=my-app",
                DownloadRequest.url("https://example.com/sg/download", parameters));
        assertEquals("https://example.com/sg/download/;group=com.acme;artifact=my-app",
                DownloadRequest.url("https://example.com/sg/download/", parameters));
    }

    @Test
    void encodesValuesThatWouldBreakThePath() {
        assertEquals("https://example.com/;projectName=My%20Project%3B%20v%3D2%2F3",
                DownloadRequest.url("https://example.com/", Map.of("projectName", "My Project; v=2/3")));
    }

    @Test
    void leavesOutBlankParametersAndTrimsTheOthers() {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("group", null);
        parameters.put("artifact", " my-app ");
        parameters.put("version", " ");

        assertEquals("https://example.com/;artifact=my-app", DownloadRequest.url("https://example.com/", parameters));
    }

    @Test
    void printsTheRequestAsACurlCommand() {
        assertEquals("curl -X GET -H \"Accept: application/octet-stream\" -o \"my-app.zip\" "
                + "\"https://example.com/sg/download/;artifact=my-app\"",
                DownloadRequest.curlCommand("https://example.com/sg/download/;artifact=my-app", "my-app"));
    }
}

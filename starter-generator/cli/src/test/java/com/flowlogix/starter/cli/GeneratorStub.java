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

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class GeneratorStub implements AutoCloseable {
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    GeneratorStub(int status, String contentType, byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getRawPath());
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
    }

    URI url() {
        return URI.create("http://localhost:%d/sg/download/".formatted(server.getAddress().getPort()));
    }

    List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static byte[] archive(String... entries) throws IOException {
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    zip.write(entry.getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return archive.toByteArray();
    }
}

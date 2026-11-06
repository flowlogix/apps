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
package com.flowlogix.starter.api;

import com.flowlogix.starter.ArchetypeGenerator;
import com.flowlogix.starter.common.DownloadRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.SneakyThrows;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Properties;

@Path(DownloadRequest.DEFAULTS_PATH)
public class DefaultsResource {
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SneakyThrows(IOException.class)
    public String defaults() {
        var properties = new Properties();
        properties.putAll(ArchetypeGenerator.defaults());
        var output = new StringWriter();
        properties.store(output, null);
        return output.toString();
    }
}

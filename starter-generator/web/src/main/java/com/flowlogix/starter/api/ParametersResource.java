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
import com.flowlogix.starter.common.ArchetypeMetadata;
import com.flowlogix.starter.common.DownloadRequest.ParameterDescriptor;
import com.flowlogix.starter.common.DownloadRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Describes the archetype parameters with their effective defaults, so that clients
 * can offer whatever this server version supports.
 */
@Path(DownloadRequest.PARAMETERS_PATH)
public class ParametersResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ParameterDescriptor> parameters() {
        var defaults = ArchetypeGenerator.defaults();
        return ArchetypeMetadata.properties().stream()
                .map(property -> ParameterDescriptor.of(property, defaults.get(property.key())))
                .toList();
    }
}

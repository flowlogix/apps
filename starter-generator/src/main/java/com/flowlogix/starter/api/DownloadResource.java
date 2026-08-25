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
import com.flowlogix.starter.ArchetypeGenerator.Parameter;
import com.flowlogix.starter.ArchetypeGenerator.ReturnValue;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Path("/")
public class DownloadResource {
    @Inject
    ArchetypeGenerator generator;

    @GET
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Response downloadFile(@MatrixParam("group") @DefaultValue("com.example") String groupId,
                                 @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                                          message = "Artifact ID can only contain letters, numbers, underscores, and hyphens")
                                 @Size(min = 1, max = 100, message = "Artifact ID must be between 1 and 100 characters")
                                 @MatrixParam("artifact") @DefaultValue("starter") String artifactId,
                                 @MatrixParam("projectName") String projectName,
                                 @MatrixParam("package") String packageName,
                                 @MatrixParam("baseType") String baseType,
                                 @MatrixParam("packagingType") String packagingType,
                                 @MatrixParam("version") String version,
                                 @MatrixParam("archetypeVersion") String archetypeVersion,
                                 @MatrixParam("useShiro") @DefaultValue("false") boolean useShiro,
                                 @MatrixParam("useAgenticAI") @DefaultValue("false") boolean useAgenticAI,
                                 @MatrixParam("useOmniFaces") @DefaultValue("false") boolean useOmniFaces,
                                 @MatrixParam("usePrimeFaces") @DefaultValue("false") boolean usePrimeFaces,
                                 @MatrixParam("useLazyModel") @DefaultValue("false") boolean useLazyModel,
                                 @MatrixParam("useMavenCache") @DefaultValue("false") boolean useMavenCache,
                                 @MatrixParam("useCodeCoverage") @DefaultValue("false") boolean useCodeCoverage,
                                 @MatrixParam("useArquillianGraphene") @DefaultValue("false") boolean useArquillianGraphene) {
        ReturnValue result = generator.generateArchetype(new Parameter[] {
                new Parameter("groupId", groupId),
                new Parameter("artifactId", artifactId),
                new Parameter("projectName", projectName),
                new Parameter("package", packageName),
                new Parameter("baseType", baseType),
                new Parameter("packagingType", packagingType),
                new Parameter("version", version),
                new Parameter("archetypeVersion", archetypeVersion),
                new Parameter("useShiro", Boolean.toString(useShiro)),
                new Parameter("useAgenticAI", Boolean.toString(useAgenticAI)),
                new Parameter("useOmniFaces", Boolean.toString(useOmniFaces)),
                new Parameter("usePrimeFaces", Boolean.toString(usePrimeFaces)),
                new Parameter("useLazyModel", Boolean.toString(useLazyModel)),
                new Parameter("useMavenCache", Boolean.toString(useMavenCache)),
                new Parameter("useCodeCoverage", Boolean.toString(useCodeCoverage)),
                new Parameter("useArquillianGraphene", Boolean.toString(useArquillianGraphene))
        });

        if (result.status() != 0) {
            result.close();
            return Response.serverError().type(MediaType.TEXT_PLAIN)
                    .entity(result.output()).build();
        }

        StreamingOutput stream = outputStream -> generator.createZipStream(result, outputStream);
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"%s.zip\"".formatted(artifactId))
                .build();
    }
}

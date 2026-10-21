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
import com.flowlogix.starter.common.ArtifactId;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import static com.flowlogix.starter.common.StarterParameters.ARCHETYPE_VERSION;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGE;

@Slf4j
@Path("/")
public class DownloadResource {
    private static final String ARCHETYPE_METADATA = "META-INF/maven/archetype-metadata.xml";
    // Allowlist of matrix parameters passed to the generator, mapped to generator parameter names
    private static final Map<String, String> ALLOWED_PARAMETERS = allowedParameters();

    @Inject
    ArchetypeGenerator generator;
    @Context
    UriInfo uriInfo;

    @GET
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response downloadFile(@MatrixParam(ARTIFACT) @DefaultValue("starter")
                                 @Pattern(regexp = ArtifactId.PATTERN,
                                          message = "Artifact ID can only contain letters, numbers, underscores, and hyphens")
                                 @Size(min = 1, max = ArtifactId.MAX_LENGTH,
                                       message = "Artifact ID must be between 1 and " + ArtifactId.MAX_LENGTH + " characters")
                                 String artifactId) {
        ReturnValue result = generator.generateArchetype(parameters(artifactId));

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

    private Parameter[] parameters(String artifactId) {
        List<PathSegment> segments = uriInfo.getPathSegments();
        Map<String, List<String>> matrix = segments.isEmpty() ? Map.of() : segments.getLast().getMatrixParameters();
        return matrix.entrySet().stream()
                .filter(entry -> ALLOWED_PARAMETERS.containsKey(entry.getKey()))
                .map(entry -> new Parameter(ALLOWED_PARAMETERS.get(entry.getKey()),
                        // repeated matrix parameters must not bypass validation
                        ARTIFACT.equals(entry.getKey()) ? artifactId : entry.getValue().getLast()))
                .toArray(Parameter[]::new);
    }

    private static Map<String, String> allowedParameters() {
        return Stream.concat(archetypePropertyNames().stream().map(name -> Map.entry(name, name)), Stream.of(
                        Map.entry(GROUP, "groupId"),
                        Map.entry(ARTIFACT, "artifactId"),
                        Map.entry(PACKAGE, "package"),
                        Map.entry(ARCHETYPE_VERSION, "archetypeVersion")))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (first, var) -> first));
    }

    // Properties declared by the archetype are the only safe pass-through names
    @SneakyThrows({IOException.class, ParserConfigurationException.class, SAXException.class})
    private static List<String> archetypePropertyNames() {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var in = DownloadResource.class.getClassLoader().getResourceAsStream(ARCHETYPE_METADATA)) {
            var nodes = factory.newDocumentBuilder().parse(in).getElementsByTagName("requiredProperty");
            return IntStream.range(0, nodes.getLength()).mapToObj(index -> ((Element) nodes.item(index))
                    .getAttribute("key")).toList();
        }
    }
}

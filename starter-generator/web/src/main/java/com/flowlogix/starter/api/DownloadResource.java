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
import com.flowlogix.starter.common.ArchetypeMetadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
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
import lombok.extern.slf4j.Slf4j;
import javax.lang.model.SourceVersion;
import static com.flowlogix.starter.common.StarterParameters.ARCHETYPE_VERSION;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGE;

@Slf4j
@Path("/")
public class DownloadResource {
    // Allowlist of matrix parameters passed to the generator, mapped to generator parameter names
    private static final Map<String, String> ALLOWED_PARAMETERS = allowedParameters();
    // Values the archetype would accept but that would produce a broken project
    private static final Map<String, Rule> RULES = rules();

    @Inject
    ArchetypeGenerator generator;
    @Context
    UriInfo uriInfo;

    @GET
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response downloadFile(@MatrixParam(ARTIFACT) @DefaultValue(DEFAULT_ARTIFACT)
                                 @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                                          message = "Artifact ID can only contain letters, numbers, underscores, and hyphens")
                                 @Size(min = 1, max = 100, message = "Artifact ID must be between 1 and 100 characters")
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
                // repeated matrix parameters must not bypass validation
                .map(entry -> parameter(entry.getKey(), ARTIFACT.equals(entry.getKey())
                        ? artifactId : entry.getValue().getLast()))
                .toArray(Parameter[]::new);
    }

    private static Parameter parameter(String name, String value) {
        Rule rule = RULES.get(name);
        if (rule != null && !value.isBlank() && !rule.isValid().test(value)) {
            throw new ConstraintViolationException("Invalid %s '%s': %s".formatted(name, value, rule.expected()), Set.of());
        }
        return new Parameter(ALLOWED_PARAMETERS.get(name), value);
    }

    private static Map<String, String> allowedParameters() {
        // Properties declared by the archetype are the only safe pass-through names
        return Stream.concat(ArchetypeMetadata.properties().stream()
                        .map(property -> Map.entry(property.key(), property.key())), Stream.of(
                        Map.entry(GROUP, "groupId"),
                        Map.entry(ARTIFACT, "artifactId"),
                        Map.entry(PACKAGE, "package"),
                        Map.entry(ARCHETYPE_VERSION, "archetypeVersion")))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (first, var) -> first));
    }

    private static Map<String, Rule> rules() {
        var rules = new HashMap<String, Rule>();
        ArchetypeMetadata.properties().stream().filter(property -> property.validationRegex() != null)
                .forEach(property -> rules.put(property.key(), new Rule(value -> value.matches(property.validationRegex()),
                        "must match " + property.validationRegex())));
        rules.put(GROUP, new Rule(value -> value.matches("[A-Za-z0-9_.-]+"), "use only letters, digits, '.', '-' and '_'"));
        rules.put(PACKAGE, new Rule(SourceVersion::isName, "not a valid Java package name"));
        return Map.copyOf(rules);
    }

    private record Rule(Predicate<String> isValid, String expected) { }
}

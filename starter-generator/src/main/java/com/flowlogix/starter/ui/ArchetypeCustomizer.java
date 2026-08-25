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
package com.flowlogix.starter.ui;

import com.flowlogix.starter.ArchetypeGenerator;
import com.flowlogix.util.ShrinkWrapManipulator;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.core.MediaType;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.omnifaces.util.Faces;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.util.Callbacks;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import static com.flowlogix.starter.ArchetypeGenerator.Parameter;
import static com.flowlogix.starter.ArchetypeGenerator.ReturnValue;

@Slf4j
@Named("archetype")
@SessionScoped
@Getter @Setter
public class ArchetypeCustomizer implements Serializable {
    @Serial
    private static final long serialVersionUID = 3L;

    @Inject
    ArchetypeGenerator generator;

    private enum BaseType {
        BASE, INFRA, PAYARA, GLASSFISH
    }

    @Pattern(regexp = "^(?!.*(?:\\.{2}|/)).*$", message = "Invalid path: Path traversal attempt detected")
    private String artifact = "";
    private String group = "";
    private String projectName = "";
    private String packageName = "";
    private BaseType baseType = BaseType.PAYARA;
    private String packagingType = "war";
    private String otherPackagingType = "";
    private String version;
    private String archetypeVersion;

    private boolean useShiro = true;
    private boolean useAgenticAI = true;
    private boolean useOmniFaces = true;
    private boolean usePrimeFaces = true;
    private boolean useLazyModel = true;
    private boolean useMavenCache = true;
    private boolean useCodeCoverage = true;
    private boolean useArquillianGraphene = true;

    public StreamedContent getDownload() {
        ReturnValue result = generator.generateArchetype(getParameters(false));
        if (result.status() != 0) {
            result.close();
            Faces.redirect(Faces.getRequestURL());
            return null;
        }

        InputStream input = generator.createZipStream(result);
        Callbacks.SerializableSupplier<InputStream> callback = () -> input;

        return DefaultStreamedContent.builder()
                .name("%s.zip".formatted(artifact.isBlank() ? "starter" : artifact.toLowerCase().trim()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .stream(callback)
                .writer(output -> generator.writer(result, input, output, true))
                .build();
    }

    private Parameter[] getParameters(boolean forCurl) {
        return new Parameter[] {
                new Parameter(forCurl ? "group" : "groupId", group.toLowerCase()),
                new Parameter(forCurl ? "artifact" : "artifactId", artifact.toLowerCase()),
                new Parameter("projectName", projectName),
                new Parameter("package", packageName.toLowerCase()),
                new Parameter("baseType", baseType.name().toLowerCase()),
                new Parameter("packagingType", processPackagingType()),
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
        };
    }

    private String processPackagingType() {
        return switch (packagingType) {
            case "jar", "JAR" -> "";
            case "other" -> "jar".equalsIgnoreCase(otherPackagingType)
                    ? "" : otherPackagingType.toLowerCase();
            case null -> "";
            default -> packagingType.toLowerCase();
        };
    }

    public String getCurlCommand() throws MalformedURLException {
        String parameters = Arrays.stream(getParameters(true))
                .filter(parameter -> parameter.value() != null && !parameter.value().isBlank())
                .map(parameter -> "%s=%s".formatted(parameter.key(),
                        URLEncoder.encode(parameter.value().trim(), StandardCharsets.UTF_8)
                                .replace("+", "%20")))
                .collect(Collectors.joining(";"));
        var baseURL = Faces.isRequestSecure()
                ? ShrinkWrapManipulator.toHttpsURL(URI.create(Faces.getRequestBaseURL()).toURL())
                : Faces.getRequestBaseURL();
        return "curl -X GET -H \"Accept: application/octet-stream\" -o \"%s.zip\" \"%sdownload/;%s\""
                .formatted(artifact.isBlank() ? "starter" : artifact.toLowerCase().trim(),
                        baseURL, parameters);
    }

    public String getMavenCommand() {
        return String.join(" ", ArchetypeGenerator.generateMavenCommandLine(
                getParameters(false), null));
    }

    public void resetSession() {
        log.debug("Resetting session");
        Faces.invalidateSession();
        Faces.redirect(Faces.getRequestURI());
    }
}

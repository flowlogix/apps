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

import com.flowlogix.starter.ArchetypeCache.Result.InvalidRecord;
import com.flowlogix.starter.ArchetypeCache.WarmupBudget;
import com.flowlogix.starter.ArchetypeGenerator;
import com.flowlogix.starter.ArchetypeCache;
import com.flowlogix.starter.common.DownloadRequest;
import com.flowlogix.util.ShrinkWrapManipulator;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.core.MediaType;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.omnifaces.util.Faces;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import java.io.Serial;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import static com.flowlogix.starter.ArchetypeGenerator.Parameter;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.defaultValue;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGING_TYPE;

@Slf4j
@Named("archetype")
@SessionScoped
@Getter @Setter
public class ArchetypeCustomizer implements Serializable {
    @Serial
    private static final long serialVersionUID = 3L;

    @Inject
    @Delegate
    ArchetypeCache cache;

    private final WarmupBudget warmupBudget = new WarmupBudget();

    private enum BaseType {
        BASE, INFRA, PAYARA, GLASSFISH
    }

    @Pattern(regexp = "^(?!.*(?:\\.{2}|/)).*$", message = "Invalid path: Path traversal attempt detected")
    private String artifact = "";
    private String group = "";
    private String projectName = "";
    private String packageName = "";
    // initial choices are the generator's defaults, so the page, the CLI and a bare request agree
    private BaseType baseType = BaseType.valueOf(defaultValue("baseType").toUpperCase());
    private String packagingType = defaultValue(PACKAGING_TYPE);
    private String otherPackagingType = "";
    private String version;
    private String archetypeVersion;

    private boolean useShiro = flag("useShiro");
    private boolean useAgenticAI = flag("useAgenticAI");
    private boolean useOmniFaces = flag("useOmniFaces");
    private boolean usePrimeFaces = flag("usePrimeFaces");
    private boolean useLazyModel = flag("useLazyModel");
    private boolean useMavenCache = flag("useMavenCache");
    private boolean useCodeCoverage = flag("useCodeCoverage");
    private boolean useArquillianGraphene = flag("useArquillianGraphene");

    public StreamedContent getDownload() {
        return switch (cache.download(getParameters(false))) {
            case InvalidRecord _ -> {
                Faces.redirect(Faces.getRequestURL());
                yield null;
            }
            case ArchetypeCache.Result.Download download -> DefaultStreamedContent.builder()
                    .name("%s.zip".formatted(artifact.isBlank() ? DEFAULT_ARTIFACT : artifact.toLowerCase().trim()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .writer(download::write)
                    .build();
        };
    }

    public void warmCache() {
        cache.warm(getParameters(false), warmupBudget);
    }

    private List<Parameter> getParameters(boolean forCurl) {
        return List.of(
                new Parameter(forCurl ? GROUP : "groupId", group.toLowerCase()),
                new Parameter(forCurl ? ARTIFACT : "artifactId", artifact.toLowerCase()),
                new Parameter("projectName", projectName),
                new Parameter("package", packageName.toLowerCase()),
                new Parameter("baseType", baseType.name().toLowerCase()),
                new Parameter(PACKAGING_TYPE, processPackagingType()),
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
        );
    }

    private String processPackagingType() {
        return switch (packagingType) {
            case "other" -> otherPackagingType.toLowerCase();
            case null -> "";
            default -> packagingType.toLowerCase();
        };
    }

    private static boolean flag(String property) {
        return Boolean.parseBoolean(defaultValue(property));
    }

    public String getCurlCommand() throws MalformedURLException {
        var parameters = getParameters(true).stream()
                .collect(Collectors.toMap(Parameter::key, Parameter::value, (_, second) -> second, LinkedHashMap::new));
        var baseURL = Faces.isRequestSecure()
                ? ShrinkWrapManipulator.toHttpsURL(URI.create(Faces.getRequestBaseURL()).toURL())
                : Faces.getRequestBaseURL();
        return DownloadRequest.curlCommand(DownloadRequest.url(DownloadRequest.endpoint(baseURL.toString()), parameters),
                artifact.isBlank() ? DEFAULT_ARTIFACT : artifact.toLowerCase().trim());
    }

    public String getMavenCommand() {
        return String.join(" ", ArchetypeGenerator.generateMavenCommandLine(getParameters(false), null));
    }

    public void resetSession() {
        log.debug("Resetting session");
        Faces.invalidateSession();
        Faces.redirect(Faces.getRequestURI());
    }
}

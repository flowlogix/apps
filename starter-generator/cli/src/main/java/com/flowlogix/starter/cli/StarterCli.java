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

import com.flowlogix.starter.cli.GeneratorClient.GeneratorException;
import com.flowlogix.starter.common.ArtifactId;
import com.flowlogix.starter.common.BaseType;
import com.flowlogix.starter.common.DownloadRequest;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

import static com.flowlogix.starter.common.StarterParameters.ARCHETYPE_VERSION;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.BASE_TYPE;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGE;
import static com.flowlogix.starter.common.StarterParameters.PACKAGING_TYPE;
import static com.flowlogix.starter.common.StarterParameters.PROJECT_NAME;
import static com.flowlogix.starter.common.StarterParameters.USE_AGENTIC_AI;
import static com.flowlogix.starter.common.StarterParameters.USE_ARQUILLIAN_GRAPHENE;
import static com.flowlogix.starter.common.StarterParameters.USE_CODE_COVERAGE;
import static com.flowlogix.starter.common.StarterParameters.USE_LAZY_MODEL;
import static com.flowlogix.starter.common.StarterParameters.USE_MAVEN_CACHE;
import static com.flowlogix.starter.common.StarterParameters.USE_OMNIFACES;
import static com.flowlogix.starter.common.StarterParameters.USE_PRIMEFACES;
import static com.flowlogix.starter.common.StarterParameters.USE_SHIRO;
import static com.flowlogix.starter.common.StarterParameters.VERSION;

/**
 * CLI front end for the Flow Logix starter generator.
 *
 * <p>It hands the options to the start.flowlogix.com generator through {@link GeneratorClient},
 * so the same options produce the same project as the web page does.
 * The validation lives here because the generator checks only the artifact id: any other
 * bad value, an unknown base type for one, would produce a broken project and still exit with 0.
 */
@Command(name = "flowlogix-starter", mixinStandardHelpOptions = true, versionProvider = StarterCli.BuildVersion.class,
        sortOptions = false, usageHelpAutoWidth = true,
        description = "Scaffolds a new Jakarta EE project with the Flow Logix starter generator.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:Project created (or request printed, with --dry-run)",
                "1:Generation failed: target directory exists, or the generator failed or could not be reached",
                "2:Invalid input: unknown option, invalid value, or missing --group-id/--artifact-id"})
public class StarterCli implements Callable<Integer> {
    /** Maven's own rule for ids, which the generator does not check. */
    private static final Pattern MAVEN_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    @Spec
    CommandSpec spec;

    @Option(names = {"-g", "--group-id"}, paramLabel = "<id>", required = true, description = "Maven groupId")
    String groupId;

    @Option(names = {"-a", "--artifact-id"}, paramLabel = "<id>", required = true,
            description = "Maven artifactId and project directory name")
    String artifactId;

    @Option(names = {"-n", "--name"}, paramLabel = "<text>", description = "Human-readable project name")
    String projectName;

    @Option(names = {"-p", "--package"}, paramLabel = "<pkg>", description = "Base Java package (default: groupId)")
    String packageName;

    @Option(names = {"-v", "--project-version"}, paramLabel = "<ver>", description = "Version of the new project")
    String projectVersion;

    @Option(names = {"-t", "--base-type"}, paramLabel = "<type>", defaultValue = "PAYARA",
            description = "Parent POM: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    BaseType baseType;

    @Option(names = {"-k", "--packaging"}, paramLabel = "<type>", defaultValue = "JAR",
            description = "Packaging: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    Packaging packaging;

    @Option(names = {"--archetype-version"}, paramLabel = "<ver>", defaultValue = "LATEST",
            description = "Pin the archetype version (default: ${DEFAULT-VALUE})")
    String archetypeVersion;

    @Option(names = {"--shiro"}, description = "Apache Shiro security")
    boolean useShiro;

    @Option(names = {"--agentic-ai"}, description = "Jakarta Agentic AI API")
    boolean useAgenticAI;

    @Option(names = {"--omnifaces"}, description = "OmniFaces")
    boolean useOmniFaces;

    @Option(names = {"--primefaces"}, description = "PrimeFaces")
    boolean usePrimeFaces;

    @Option(names = {"--lazy-model"}, description = "JPA Lazy DataModel")
    boolean useLazyModel;

    @Option(names = {"--maven-cache"}, description = "Maven build cache")
    boolean useMavenCache;

    @Option(names = {"--code-coverage"}, description = "JaCoCo code coverage")
    boolean useCodeCoverage;

    @Option(names = {"--arquillian-graphene"}, description = "Arquillian Graphene tests")
    boolean useArquillianGraphene;

    @Option(names = {"-o", "--output-dir"}, paramLabel = "<dir>", defaultValue = ".",
            description = "Where the project directory is created (default: current directory)")
    Path outputDir;

    @Option(names = {"--generator-url"}, paramLabel = "<url>",
            defaultValue = "https://start.flowlogix.com/sg/download/",
            description = "Starter generator to call (default: ${DEFAULT-VALUE})")
    URI generatorUrl;

    @Option(names = {"--dry-run"}, description = "Validate the options, print the equivalent curl command and exit")
    boolean dryRun;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Lets base type and packaging be typed in lower case, the way the generator takes them. */
    static CommandLine commandLine() {
        return new CommandLine(new StarterCli()).setCaseInsensitiveEnumValuesAllowed(true);
    }

    enum Packaging {
        JAR, WAR, EAR
    }

    /** Reports the version the build stamps into the jar manifest. */
    static class BuildVersion implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = StarterCli.class.getPackage().getImplementationVersion();
            return new String[] {"flowlogix-starter " + Objects.requireNonNullElse(version, "(development build)")};
        }
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        validate();

        Path projectDir = outputDir.resolve(artifactId).toAbsolutePath().normalize();
        if (Files.exists(projectDir)) {
            spec.commandLine().getErr().printf("Directory '%s' already exists. "
                    + "Choose another --artifact-id or --output-dir.%n", projectDir);
            return 1;
        }

        String request = DownloadRequest.url(generatorUrl.toString(), parameters());
        if (dryRun) {
            spec.commandLine().getOut().println(DownloadRequest.curlCommand(request, artifactId));
            return 0;
        }

        spec.commandLine().getOut().printf("Generating %s through %s, this takes a few seconds...%n",
                projectDir, generatorUrl.getHost());
        try {
            new GeneratorClient(generatorUrl).generate(URI.create(request), artifactId, projectDir);
        } catch (GeneratorException e) {
            spec.commandLine().getErr().println(e.getMessage());
            // 2 only for a rejection, so that it keeps meaning invalid input
            return e.rejected() ? 2 : 1;
        }
        spec.commandLine().getOut().printf("Created %s%n  cd %s && ./mvnw verify%n", projectDir, projectDir);
        return 0;
    }

    /** The checks the generator does not make; see the class comment. */
    private void validate() {
        if (!MAVEN_ID.matcher(groupId).matches()) {
            throw invalid("--group-id", groupId, "use only letters, digits, '.', '-' and '_'");
        }
        if (!ArtifactId.isValid(artifactId)) {
            throw invalid("--artifact-id", artifactId,
                    "use 1 to %d letters, digits, '-' or '_'".formatted(ArtifactId.MAX_LENGTH));
        }
        if (!isBlank(packageName) && !SourceVersion.isName(packageName)) {
            throw invalid("--package", packageName,
                    "not a valid Java package name: each part must be an identifier, not a Java keyword");
        }
        if (isBlank(packageName) && !SourceVersion.isName(groupId)) {
            throw invalid("--group-id", groupId,
                    "the base package defaults to the groupId, which is not a valid Java package name; "
                    + "set one with --package");
        }
        String scheme = generatorUrl.getScheme();
        if (generatorUrl.getHost() == null || !("https".equals(scheme) || "http".equals(scheme))) {
            throw invalid("--generator-url", generatorUrl.toString(), "expected an http or https URL");
        }
    }

    private ParameterException invalid(String option, String value, String reason) {
        return new ParameterException(spec.commandLine(),
                "Invalid value for option '%s': '%s' (%s)".formatted(option, value, reason));
    }

    /** The options the user set, under the generator's parameter names. */
    Map<String, String> parameters() {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put(GROUP, groupId);
        parameters.put(ARTIFACT, artifactId);
        // always sent: left out, the generator uses com.example.starter instead of the groupId
        parameters.put(PACKAGE, isBlank(packageName) ? groupId : packageName);
        parameters.put(PROJECT_NAME, projectName);
        parameters.put(VERSION, projectVersion);
        parameters.put(BASE_TYPE, lowerCase(baseType));
        parameters.put(PACKAGING_TYPE, lowerCase(packaging));
        parameters.put(ARCHETYPE_VERSION, archetypeVersion);
        parameters.put(USE_SHIRO, flag(useShiro));
        parameters.put(USE_AGENTIC_AI, flag(useAgenticAI));
        parameters.put(USE_OMNIFACES, flag(useOmniFaces));
        parameters.put(USE_PRIMEFACES, flag(usePrimeFaces));
        parameters.put(USE_LAZY_MODEL, flag(useLazyModel));
        parameters.put(USE_MAVEN_CACHE, flag(useMavenCache));
        parameters.put(USE_CODE_COVERAGE, flag(useCodeCoverage));
        parameters.put(USE_ARQUILLIAN_GRAPHENE, flag(useArquillianGraphene));
        parameters.values().removeIf(StarterCli::isBlank);
        return parameters;
    }

    private static String lowerCase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String flag(boolean value) {
        return value ? "true" : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

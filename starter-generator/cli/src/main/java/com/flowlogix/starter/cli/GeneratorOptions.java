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

import com.flowlogix.starter.common.ArchetypeMetadata;
import com.flowlogix.starter.common.DownloadRequest.ParameterDescriptor;
import com.flowlogix.starter.common.StarterParameters;
import com.flowlogix.starter.common.DownloadRequest;
import com.flowlogix.starter.cli.GeneratorClient.GeneratorException;
import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.flowlogix.starter.common.StarterParameters.ARCHETYPE_VERSION;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_GROUP;
import static com.flowlogix.starter.common.StarterParameters.DEFAULT_PACKAGE;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGE;
import static com.flowlogix.starter.common.StarterParameters.PACKAGING_TYPE;

/**
 * The generator's parameters as command line options: one for each property the archetype declares,
 * so that a new property becomes an option without a change here.
 */
@ApplicationScoped
class GeneratorOptions {
    // --version prints the version of the command line itself
    private static final Map<String, String> RENAMED = Map.of("version", "projectVersion");
    private static final List<String> PACKAGING_ALIASES = List.of("jar", "war", "ear");
    private final Map<String, OptionSpec> options = new LinkedHashMap<>();
    private final Map<String, OptionSpec> packagingAliases = new LinkedHashMap<>();

    @Produces
    CommandLine commandLine(PicocliCommandLineFactory factory) {
        add(GROUP, OptionSpec.builder("-g", "--" + GROUP).defaultValue(DEFAULT_GROUP).paramLabel("<id>")
                .description("Maven groupId (default: ${DEFAULT-VALUE})"));
        add(ARTIFACT, OptionSpec.builder("-a", "--" + ARTIFACT).defaultValue(DEFAULT_ARTIFACT).paramLabel("<id>")
                .description("Maven artifactId and project directory name (default: ${DEFAULT-VALUE})"));
        add(PACKAGE, OptionSpec.builder("-p", "--" + PACKAGE).paramLabel("<package>")
                .description("Base Java package (default: ${DEFAULT-VALUE})").defaultValue(DEFAULT_PACKAGE));
        add(ARCHETYPE_VERSION, OptionSpec.builder("--" + ARCHETYPE_VERSION).paramLabel("<version>")
                .description("Version of the starter archetype"));
        ArchetypeMetadata.properties().forEach(property -> options.put(property.key(),
                option(ParameterDescriptor.of(property, StarterParameters.defaultValue(property)), null).build()));

        CommandLine commandLine = factory.create();
        commandLine.setOptionsCaseInsensitive(true);
        options.values().forEach(commandLine.getCommandSpec()::addOption);
        PACKAGING_ALIASES.forEach(type -> {
            var alias = OptionSpec.builder("--" + type).type(boolean.class)
                    .description("Same as --" + PACKAGING_TYPE + "=" + type).build();
            packagingAliases.put(type, alias);
            commandLine.getCommandSpec().addOption(alias);
        });
        var execution = commandLine.getExecutionStrategy();
        // options the server added since this build are not known yet, see withServerParameters
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.setExecutionStrategy(result -> execution.execute(withServerParameters(commandLine, result)));
        return commandLine;
    }

    /**
     * The options given on the command line, under the generator's parameter names.
     * Omitted archetype properties are resolved by the server.
     */
    Map<String, String> parameters() {
        var parameters = new LinkedHashMap<String, String>();
        options.forEach((name, option) -> {
            String value = Objects.toString(option.getValue(), null);
            if (value != null) {
                parameters.put(name, value);
            }
        });
        var requested = packagingAliases.entrySet().stream()
                .filter(alias -> Boolean.TRUE.equals(alias.getValue().getValue())).map(Map.Entry::getKey).toList();
        if (requested.size() > 1 || !requested.isEmpty() && parameters.containsKey(PACKAGING_TYPE)) {
            throw new CommandLine.ParameterException(packagingAliases.values().iterator().next().command().commandLine(),
                    "--jar, --war, --ear and --" + PACKAGING_TYPE + " are mutually exclusive");
        }
        requested.forEach(type -> parameters.put(PACKAGING_TYPE, type));
        return parameters;
    }

    String value(String parameter) {
        return Objects.toString(options.get(parameter).getValue(), null);
    }

    static String optionName(String property) {
        return RENAMED.getOrDefault(property, property);
    }

    private static OptionSpec.Builder option(ParameterDescriptor parameter, String shownDefault) {
        var option = OptionSpec.builder("--" + optionName(parameter.key()))
                .type(parameter.flag() ? Boolean.class : String.class).description(description(parameter, shownDefault));
        return parameter.flag() ? option.arity("0..1").fallbackValue(Boolean.TRUE.toString())
                .paramLabel("<true|false>") : option.paramLabel("<value>");
    }

    /**
     * Offers the parameters this version of the server supports, which can differ from the ones built into the CLI.
     * They are needed to show the server's defaults in the help, and to recognize options this CLI does not know.
     */
    private ParseResult withServerParameters(CommandLine commandLine, ParseResult result) {
        boolean help = result.isUsageHelpRequested();
        if (!help && result.unmatched().isEmpty()) {
            return result;
        }
        if (help && value(ARCHETYPE_VERSION) != null && !"LATEST".equals(value(ARCHETYPE_VERSION))) {
            commandLine.getErr().println("Warning: defaults omitted for an explicitly selected archetype version.");
            return result;
        }
        StarterCli command = commandLine.getCommand();
        URI endpoint = URI.create(DownloadRequest.apiEndpoint(command.generatorUrl.toString()));
        try {
            addServerParameters(commandLine.getCommandSpec(), new GeneratorClient(endpoint).parameters());
        } catch (GeneratorException e) {
            commandLine.getErr().println("Warning: server parameters unavailable at " + endpoint
                    + DownloadRequest.PARAMETERS_PATH + "; using the options built into this version: " + e.getMessage());
        }
        if (help) {
            return result;
        }
        // whatever is still unknown now is a mistake
        commandLine.setUnmatchedArgumentsAllowed(false);
        return commandLine.parseArgs(result.originalArgs().toArray(String[]::new));
    }

    private void addServerParameters(CommandSpec spec, List<ParameterDescriptor> parameters) {
        for (ParameterDescriptor parameter : parameters) {
            var previous = options.get(parameter.key());
            var option = option(parameter, parameter.defaultValue()).build();
            if (previous != null) {
                spec.remove(previous);
            }
            spec.addOption(option);
            options.put(parameter.key(), option);
        }
    }

    private static String description(ParameterDescriptor parameter, String defaultValue) {
        return Stream.of(defaultValue == null ? null : "default: " + defaultValue,
                        parameter.validationRegex() == null ? null : "must match " + parameter.validationRegex())
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private void add(String parameter, OptionSpec.Builder option) {
        options.put(parameter, option.type(String.class).build());
    }
}

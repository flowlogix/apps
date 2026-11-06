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
import com.flowlogix.starter.common.ArchetypeMetadata.Property;
import com.flowlogix.starter.common.StarterParameters;
import com.flowlogix.starter.common.DownloadRequest;
import com.flowlogix.starter.cli.GeneratorClient.GeneratorException;
import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import java.net.URI;
import java.util.LinkedHashMap;
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

/**
 * The generator's parameters as command line options: one for each property the archetype declares,
 * so that a new property becomes an option without a change here.
 */
@ApplicationScoped
class GeneratorOptions {
    // --version prints the version of the command line itself
    private static final Map<String, String> RENAMED = Map.of("version", "projectVersion");
    private final Map<String, OptionSpec> options = new LinkedHashMap<>();

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
        ArchetypeMetadata.properties().forEach(property -> add(property.key(), option(property)));

        CommandLine commandLine = factory.create();
        options.values().forEach(commandLine.getCommandSpec()::addOption);
        var execution = commandLine.getExecutionStrategy();
        commandLine.setExecutionStrategy(result -> {
            if (result.isUsageHelpRequested()) {
                StarterCli command = commandLine.getCommand();
                updateHelpDefaults(commandLine, command.generatorUrl);
            }
            return execution.execute(result);
        });
        return commandLine;
    }

    /**
     * The options given on the command line, under the generator's parameter names.
     * Omitted archetype properties are resolved by the server.
     */
    Map<String, String> parameters() {
        var parameters = new LinkedHashMap<String, String>();
        options.forEach((name, option) -> {
            String value = option.getValue();
            if (value != null) {
                parameters.put(name, value);
            }
        });
        return parameters;
    }

    String value(String parameter) {
        return options.get(parameter).getValue();
    }

    static String optionName(String property) {
        return RENAMED.getOrDefault(property, property);
    }

    private static OptionSpec.Builder option(Property property) {
        String defaultValue = StarterParameters.defaultValue(property);
        boolean flag = "true".equals(defaultValue) || "false".equals(defaultValue);
        var option = OptionSpec.builder("--" + optionName(property.key()))
                .description(description(property, null));
        return flag ? option.arity("0..1").fallbackValue("true").paramLabel("<true|false>")
                : option.paramLabel("<value>");
    }

    private void updateHelpDefaults(CommandLine commandLine, URI generatorUrl) {
        if (value(ARCHETYPE_VERSION) != null && !"LATEST".equals(value(ARCHETYPE_VERSION))) {
            commandLine.getErr().println("Warning: defaults omitted for an explicitly selected archetype version.");
            return;
        }
        URI endpoint = URI.create(DownloadRequest.apiEndpoint(generatorUrl.toString()));
        try {
            var defaults = new GeneratorClient(endpoint).defaults();
            ArchetypeMetadata.properties().forEach(property -> {
                var previous = options.get(property.key());
                var option = previous.toBuilder()
                        .description(description(property, defaults.getProperty(property.key()))).build();
                commandLine.getCommandSpec().remove(previous).addOption(option);
                options.put(property.key(), option);
            });
        } catch (GeneratorException e) {
            commandLine.getErr().println("Warning: server defaults unavailable at " + endpoint
                    + DownloadRequest.DEFAULTS_PATH + "; defaults omitted: " + e.getMessage());
        }
    }

    private static String description(Property property, String defaultValue) {
        return Stream.of(defaultValue == null ? null : "default: " + defaultValue,
                        property.validationRegex() == null ? null : "must match " + property.validationRegex())
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private void add(String parameter, OptionSpec.Builder option) {
        options.put(parameter, option.type(String.class).build());
    }
}

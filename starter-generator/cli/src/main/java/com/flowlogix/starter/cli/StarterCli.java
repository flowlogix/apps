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
import com.flowlogix.starter.common.DownloadRequest;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import static com.flowlogix.starter.common.StarterParameters.ARTIFACT;
import static com.flowlogix.starter.common.StarterParameters.GROUP;
import static com.flowlogix.starter.common.StarterParameters.PACKAGE;

/**
 * CLI front end for the Flow Logix starter generator.
 *
 * <p>It hands the options to the start.flowlogix.com generator, so the same options produce the same project
 * as the web page does, and the generator alone decides which values are valid.
 */
@TopCommand
@Command(name = "flowlogix-starter", mixinStandardHelpOptions = true, versionProvider = StarterCli.BuildVersion.class,
        usageHelpAutoWidth = true,
        description = "Scaffolds a new Jakarta EE project with the Flow Logix starter generator.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:Project created (or request printed, with --dry-run)",
                "1:Generation failed: target directory exists, or the generator failed or could not be reached",
                "2:Invalid input, refused by the command line or by the generator"})
@RequiredArgsConstructor
public class StarterCli implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Option(names = {"-o", "--output-dir"}, paramLabel = "<dir>", defaultValue = ".",
            description = "Where the project directory is created (default: current directory)")
    Path outputDir;

    @Option(names = "--generator-url", paramLabel = "<url>", defaultValue = "https://start.flowlogix.com/sg/download/",
            description = "Starter generator to call (default: ${DEFAULT-VALUE})")
    URI generatorUrl;

    @Option(names = "--dry-run", description = "Print the equivalent curl command and exit")
    boolean dryRun;

    private final GeneratorOptions options;

    /** Reports the version of the build. */
    static class BuildVersion implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"flowlogix-starter "
                    + ConfigProvider.getConfig().getValue("quarkus.application.version", String.class)};
        }
    }

    @Override
    public Integer call() {
        Path projectDir = outputDir.resolve(options.value(ARTIFACT)).toAbsolutePath().normalize();
        if (Files.exists(projectDir)) {
            spec.commandLine().getErr().printf("Directory '%s' already exists. "
                    + "Choose another --artifact or --output-dir.%n", projectDir);
            return 1;
        }

        Map<String, String> parameters = options.parameters();
        // left out, the generator uses com.example.starter instead of the group
        parameters.putIfAbsent(PACKAGE, parameters.get(GROUP));
        if (dryRun) {
            spec.commandLine().getOut().println(DownloadRequest.curlCommand(
                    DownloadRequest.url(generatorUrl.toString(), parameters), options.value(ARTIFACT)));
            return 0;
        }

        spec.commandLine().getOut().printf("Generating %s through %s, this takes a few seconds...%n",
                projectDir, generatorUrl.getHost());
        try {
            new GeneratorClient(generatorUrl).generate(parameters, projectDir);
        } catch (GeneratorException e) {
            spec.commandLine().getErr().println(e.getMessage());
            // 2 only for a rejection, so that it keeps meaning invalid input
            return e.isRejected() ? 2 : 1;
        }
        spec.commandLine().getOut().printf("Created %s%n  cd %s && ./mvnw verify%n", projectDir, projectDir);
        return 0;
    }
}

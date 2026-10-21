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
package com.flowlogix.starter;

import com.flowlogix.starter.ArchetypeGenerator.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import static com.flowlogix.starter.ArchetypeGenerator.ReturnValue;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class ArchetypeRunnerIT {
    @Test
    void generateArchetype() throws IOException, ExecutionException, InterruptedException {
        var path = Path.of("target/starter.zip");
        Path temporaryPath;
        var archetypeGenerator = new ArchetypeGenerator();
        try (var executor = Executors.newSingleThreadExecutor();
             ReturnValue result = archetypeGenerator.generateArchetype(new Parameter[] {
                     new Parameter("package", "com.example.starter")})) {
            archetypeGenerator.zipToStream(result, new BufferedOutputStream(
                    new FileOutputStream(path.toFile())), executor).get();
            assertThat(result.status()).withFailMessage(result.output()).isZero();
            assertThat(path).isNotEmptyFile();
            temporaryPath = result.temporaryPath();
            log.debug("Generated project: {}", result.output());
            assertThat(temporaryPath).isNotEmptyDirectory();
            assertThat(temporaryPath.resolve(".mvn")).doesNotExist();
        }
        assertThat(temporaryPath).doesNotExist();
    }
}

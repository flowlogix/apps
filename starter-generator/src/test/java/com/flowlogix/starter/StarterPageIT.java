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

import com.flowlogix.test.AppServerLifecycle;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.graphene.page.Page;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.net.URL;
import static com.flowlogix.starter.StarterPage.FEATURES;
import static org.assertj.core.api.Assertions.assertThat;

@RunAsClient
@Tag("UserInterface")
@AppServerLifecycle
class StarterPageIT {
    @ArquillianResource
    private URL baseURL;

    @Page
    private StarterPage page;

    @BeforeEach
    void openStarterPage() {
        page.open(baseURL);
    }

    @Test
    void defaults() {
        assertThat(page.getWebDriver().getTitle()).isEqualTo("Flow Logix Maven and Jakarta EE Quick Start");
        assertThat(page.getGenerateButtons()).hasSize(2);
        assertThat(page.textInputs()).allSatisfy(input -> assertThat(input.getDomProperty("value")).isEmpty());
        assertThat(page.getOtherPackagingType()).isEmpty();
        assertThat(page.curlCommand())
                .startsWith("curl -X GET -H \"Accept: application/octet-stream\" -o \"starter.zip\" \"%sdownload/;"
                        .formatted(baseURL))
                .contains("baseType=payara;packagingType=war;")
                .contains(FEATURES.stream().map(feature -> feature + "=true").toList())
                .doesNotContain("group=", "artifact=", "projectName=", "package=");
        assertThat(page.mavenCommand())
                .startsWith("mvn archetype:generate -DarchetypeGroupId=\"com.flowlogix.archetypes\" "
                        + "-DarchetypeArtifactId=\"starter\" -DarchetypeVersion=\"LATEST\"")
                .contains("-DgroupId=\"com.example\"", "-DartifactId=\"starter\"",
                        "-Dpackage=\"com.example.starter\"", "-DbaseType=\"payara\"", "-DpackagingType=\"war\"")
                .doesNotContain("outputDirectory");
    }

    @Test
    void projectConfiguration() {
        page.getArtifact().sendKeys(" MyApp ");
        page.getGroup().sendKeys("com.Acme");
        page.getProjectName().sendKeys("My App");
        page.getPackageName().sendKeys("com.acme.MyApp");
        page.getVersion().sendKeys("2.0");
        page.getArchetypeVersion().sendKeys("154");
        page.updateCommands();

        assertThat(page.curlCommand()).contains("-o \"myapp.zip\"",
                "download/;group=com.acme;artifact=myapp;projectName=My%20App;package=com.acme.myapp;",
                ";version=2.0;archetypeVersion=154;");
        assertThat(page.mavenCommand()).contains("-DarchetypeVersion=\"154\"", "-DgroupId=\"com.acme\"",
                "-DartifactId=\"myapp\"", "-DprojectName=\"My App\"", "-Dpackage=\"com.acme.myapp\"",
                "-Dversion=\"2.0\"");
    }

    @Test
    void baseType() {
        page.selectBaseType("GlassFish");
        assertThat(page.curlCommand()).contains("baseType=glassfish;");
        assertThat(page.mavenCommand()).contains("-DbaseType=\"glassfish\"");
    }

    @Test
    void jarPackaging() {
        page.selectPackagingType("JAR");
        assertThat(page.getOtherPackagingType()).isEmpty();
        assertThat(page.curlCommand()).doesNotContain("packagingType");
        assertThat(page.mavenCommand()).doesNotContain("packagingType");
    }

    @Test
    void otherPackaging() {
        page.selectPackagingType("Other");
        assertThat(page.getOtherPackagingType()).hasSize(1);
        page.getOtherPackagingType().getFirst().sendKeys("POM");
        page.updateCommands();
        assertThat(page.curlCommand()).contains("packagingType=pom;");
        assertThat(page.mavenCommand()).contains("-DpackagingType=\"pom\"");

        page.selectPackagingType("EAR");
        assertThat(page.getOtherPackagingType()).isEmpty();
        assertThat(page.curlCommand()).contains("packagingType=ear;");
    }

    @Test
    void features() {
        for (String feature : FEATURES) {
            page.toggleFeature(feature);
            assertThat(page.curlCommand()).contains(feature + "=false");
            assertThat(page.mavenCommand()).contains("-D%s=\"false\"".formatted(feature));
        }
        page.toggleFeature("useShiro");
        assertThat(page.curlCommand()).contains("useShiro=true", "useAgenticAI=false");
    }

    @Test
    void pathTraversalRejected() {
        page.getArtifact().sendKeys("../evil");
        page.getGroup().sendKeys("com.acme");
        page.updateCommandsRejected();
        assertThat(page.curlCommand()).doesNotContain("evil");
        assertThat(page.mavenCommand()).doesNotContain("evil");
    }

    @Test
    void resetFields() {
        page.getArtifact().sendKeys("myapp");
        page.toggleFeature("useShiro");
        assertThat(page.curlCommand()).contains("artifact=myapp;", "useShiro=false");

        page.reset();
        assertThat(page.getArtifact().getDomProperty("value")).isEmpty();
        assertThat(page.curlCommand()).contains("-o \"starter.zip\"", "useShiro=true").doesNotContain("artifact=");
    }
}

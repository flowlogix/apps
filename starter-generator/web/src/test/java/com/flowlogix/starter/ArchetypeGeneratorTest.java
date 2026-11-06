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
import com.flowlogix.starter.api.DefaultsResource;
import com.flowlogix.starter.common.StarterParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

@Isolated
class ArchetypeGeneratorTest {
    private final String originalShiroVersion = System.getProperty("shiroVersion");

    @AfterEach
    void restoreShiroVersion() {
        if (originalShiroVersion == null) {
            System.clearProperty("shiroVersion");
        } else {
            System.setProperty("shiroVersion", originalShiroVersion);
        }
    }

    @Test
    void serverShiroDefaultIsSharedByMetadataGenerationAndCacheKeys() throws IOException {
        System.setProperty("shiroVersion", " server-shiro ");
        var defaults = new Properties();
        defaults.load(new StringReader(new DefaultsResource().defaults()));

        assertThat(defaults.getProperty("shiroVersion")).isEqualTo("server-shiro");
        assertThat(defaults.getProperty("packagingType")).isEqualTo(StarterParameters.defaultValue("packagingType"));
        assertThat(ArchetypeGenerator.generateMavenCommandLine(null, null))
                .contains("-DshiroVersion=\"server-shiro\"");
        var explicit = new Parameter[] {new Parameter("shiroVersion", "custom-shiro")};
        assertThat(ArchetypeGenerator.generateMavenCommandLine(explicit, null))
                .contains("-DshiroVersion=\"custom-shiro\"").doesNotContain("-DshiroVersion=\"server-shiro\"");
        assertThat(ArchetypeGenerator.cacheKey(null))
                .isEqualTo(ArchetypeGenerator.cacheKey(new Parameter[] {
                        new Parameter("shiroVersion", "server-shiro")}))
                .isNotEqualTo(ArchetypeGenerator.cacheKey(explicit));
    }

    @Test
    void blankShiroOverrideLeavesArchetypeDefaultUnspecified() {
        System.setProperty("shiroVersion", " ");

        assertThat(ArchetypeGenerator.defaults().get("shiroVersion"))
                .isEqualTo(StarterParameters.defaultValue("shiroVersion"));
        assertThat(ArchetypeGenerator.generateMavenCommandLine(null, null))
                .noneMatch(argument -> argument.startsWith("-DshiroVersion="));
    }
}

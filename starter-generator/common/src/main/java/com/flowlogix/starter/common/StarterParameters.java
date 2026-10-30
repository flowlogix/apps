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
package com.flowlogix.starter.common;

import com.flowlogix.starter.common.ArchetypeMetadata.Property;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * Names the generator's download endpoint gives to parameters that are not properties of the archetype.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings({"checkstyle:JavadocVariable", "HideUtilityClassConstructor"})
public class StarterParameters {
    public static final String GROUP = "group";
    public static final String ARTIFACT = "artifact";
    public static final String PACKAGE = "package";
    public static final String ARCHETYPE_VERSION = "archetypeVersion";
    /** Group the generator uses when none is given. */
    public static final String DEFAULT_GROUP = "com.example";
    /** Artifact the generator uses when none is given. */
    public static final String DEFAULT_ARTIFACT = "starter";
    /**
     * Values the generator uses for parameters that are left out, where it does not follow the archetype's own
     * defaults. They are the web page's initial choices too, so the page, the CLI and a bare request agree.
     */
    public static final Map<String, String> DEFAULTS = Map.of(
            "projectName", "Starter Project",
            "version", "1.x-SNAPSHOT",
            "baseType", "payara",
            "packagingType", "war",
            "useShiro", "true",
            "useAgenticAI", "true",
            "useOmniFaces", "true",
            "usePrimeFaces", "true",
            "useLazyModel", "true");

    /**
     * The value the generator uses for a property that is left out: its own default, or else the archetype's.
     */
    public static String defaultValue(Property property) {
        return DEFAULTS.getOrDefault(property.key(), property.defaultValue());
    }

    /**
     * The value the generator uses for a property that is left out, by its name.
     */
    public static String defaultValue(String property) {
        return ArchetypeMetadata.properties().stream().filter(candidate -> candidate.key().equals(property))
                .findFirst().map(StarterParameters::defaultValue).orElseGet(() -> DEFAULTS.get(property));
    }
}

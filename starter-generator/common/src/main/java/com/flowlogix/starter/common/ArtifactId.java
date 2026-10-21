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

/**
 * The rule the generator applies to artifact ids, for its clients to apply before they call it.
 */
public final class ArtifactId {
    /** Characters an artifact id may have. */
    public static final String PATTERN = "^[a-zA-Z0-9_-]+$";
    /** Longest artifact id the generator takes. */
    public static final int MAX_LENGTH = 100;

    private ArtifactId() {
    }

    public static boolean isValid(String artifactId) {
        return artifactId.length() <= MAX_LENGTH && artifactId.matches(PATTERN);
    }
}

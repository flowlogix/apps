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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactIdTest {
    @Test
    void acceptsLettersDigitsHyphensAndUnderscores() {
        assertTrue(ArtifactId.isValid("My_app-2"));
    }

    @Test
    void rejectsAnythingElse() {
        assertFalse(ArtifactId.isValid(""));
        assertFalse(ArtifactId.isValid("my app"));
        assertFalse(ArtifactId.isValid("my.app"));
        assertFalse(ArtifactId.isValid("../app"));
        assertFalse(ArtifactId.isValid("app\n"));
    }

    @Test
    void limitsTheLength() {
        assertTrue(ArtifactId.isValid("a".repeat(ArtifactId.MAX_LENGTH)));
        assertFalse(ArtifactId.isValid("a".repeat(ArtifactId.MAX_LENGTH + 1)));
    }
}

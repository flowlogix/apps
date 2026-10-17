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
 * Names of the parameters the generator's download endpoint takes.
 */
@SuppressWarnings("checkstyle:JavadocVariable")
public final class StarterParameters {
    public static final String GROUP = "group";
    public static final String ARTIFACT = "artifact";
    public static final String PROJECT_NAME = "projectName";
    public static final String PACKAGE = "package";
    public static final String BASE_TYPE = "baseType";
    public static final String PACKAGING_TYPE = "packagingType";
    public static final String VERSION = "version";
    public static final String ARCHETYPE_VERSION = "archetypeVersion";
    public static final String USE_SHIRO = "useShiro";
    public static final String USE_AGENTIC_AI = "useAgenticAI";
    public static final String USE_OMNIFACES = "useOmniFaces";
    public static final String USE_PRIMEFACES = "usePrimeFaces";
    public static final String USE_LAZY_MODEL = "useLazyModel";
    public static final String USE_MAVEN_CACHE = "useMavenCache";
    public static final String USE_CODE_COVERAGE = "useCodeCoverage";
    public static final String USE_ARQUILLIAN_GRAPHENE = "useArquillianGraphene";

    private StarterParameters() {
    }
}

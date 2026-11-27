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
/**
 * JStarter, the Jakarta EE Starter by Flow Logix: the command line front end of the starter generator.
 */
module com.flowlogix.starter.cli {
    requires static lombok;
    requires com.flowlogix.starter.common;
    requires info.picocli;
    requires io.quarkus.core;
    requires quarkus.picocli;
    requires jakarta.cdi;
    requires jakarta.json.bind;
    requires jakarta.ws.rs;
    requires org.eclipse.microprofile.config;
    requires microprofile.rest.client.api;
    requires org.apache.commons.compress;
    requires org.apache.commons.io;
    requires org.apache.commons.lang3;
    // only GeneratorStub, a test, serves the generator API with the JDK's HTTP server
    requires static jdk.httpserver;

    // picocli, CDI and JSON-B reach the CLI's classes reflectively
    opens com.flowlogix.starter.cli;
}

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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Properties the starter archetype declares, read from its own descriptor.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings("HideUtilityClassConstructor")
public class ArchetypeMetadata {
    private static final String DESCRIPTOR = "META-INF/maven/archetype-metadata.xml";
    private static final List<Property> PROPERTIES = read();

    /**
     * A property of the archetype.
     *
     * @param key name of the property
     * @param defaultValue value the archetype uses when none is given, or {@code null}
     * @param validationRegex pattern a given value has to match, or {@code null}
     */
    public record Property(String key, String defaultValue, String validationRegex) { }

    /**
     * The archetype's required properties, in the order it declares them.
     */
    public static List<Property> properties() {
        return PROPERTIES;
    }

    @SneakyThrows({IOException.class, ParserConfigurationException.class, SAXException.class})
    private static List<Property> read() {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var in = ArchetypeMetadata.class.getClassLoader().getResourceAsStream(DESCRIPTOR)) {
            var nodes = factory.newDocumentBuilder().parse(in).getElementsByTagName("requiredProperty");
            return IntStream.range(0, nodes.getLength()).mapToObj(index -> (Element) nodes.item(index))
                    .map(property -> new Property(property.getAttribute("key"),
                            text(property, "defaultValue"), text(property, "validationRegex")))
                    .toList();
        }
    }

    private static String text(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }
}

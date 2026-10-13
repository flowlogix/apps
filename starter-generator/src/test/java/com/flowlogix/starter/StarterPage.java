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

import lombok.Getter;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import java.net.URL;
import java.util.List;
import static org.jboss.arquillian.graphene.Graphene.guardAjax;
import static org.jboss.arquillian.graphene.Graphene.guardHttp;
import static org.jboss.arquillian.graphene.Graphene.guardNoRequest;
import static org.jboss.arquillian.graphene.Graphene.waitGui;

/**
 * Graphene page object for starter.xhtml.
 */
@Getter
public class StarterPage {
    static final List<String> FEATURES = List.of("useShiro", "useAgenticAI", "useOmniFaces", "usePrimeFaces",
            "useLazyModel", "useMavenCache", "useCodeCoverage", "useArquillianGraphene");

    @Drone
    private WebDriver webDriver;

    @FindBy(id = "starterForm:artifact")
    private WebElement artifact;

    @FindBy(id = "starterForm:group")
    private WebElement group;

    @FindBy(id = "starterForm:projectName")
    private WebElement projectName;

    @FindBy(id = "starterForm:packageName")
    private WebElement packageName;

    @FindBy(id = "starterForm:version")
    private WebElement version;

    @FindBy(id = "starterForm:archetypeVersion")
    private WebElement archetypeVersion;

    @FindBy(id = "starterForm:baseType")
    private WebElement baseType;

    @FindBy(id = "starterForm:packagingType")
    private WebElement packagingType;

    // only rendered when packaging type is other
    @FindBy(id = "starterForm:otherPackagingTypeInput")
    private List<WebElement> otherPackagingType;

    @FindBy(className = "download-button")
    private List<WebElement> generateButtons;

    @FindBy(id = "starterForm:updateCommands")
    private WebElement updateCommandsButton;

    @FindBy(id = "resetForm:reset")
    private WebElement resetButton;

    @FindBy(id = "starterForm:curlCommand")
    private WebElement curlCommandLabel;

    @FindBy(id = "starterForm:mavenCommand")
    private WebElement mavenCommandLabel;

    /**
     * Opens the page with a new session, because the backing bean is session scoped.
     */
    public void open(URL baseURL) {
        webDriver.get(baseURL + "starter");
        webDriver.manage().deleteAllCookies();
        webDriver.get(baseURL + "starter");
        // p:focus moves the focus once the page is loaded, typing before that ends up in the wrong field
        waitGui(webDriver).until(driver -> artifact.equals(driver.switchTo().activeElement()));
    }

    public List<WebElement> textInputs() {
        return List.of(artifact, group, projectName, packageName, version, archetypeVersion);
    }

    public String curlCommand() {
        return curlCommandLabel.getText();
    }

    public String mavenCommand() {
        return mavenCommandLabel.getText();
    }

    public void updateCommands() {
        guardAjax(updateCommandsButton).click();
    }

    /**
     * For input that is rejected by client side validation.
     */
    public void updateCommandsRejected() {
        guardNoRequest(updateCommandsButton).click();
    }

    public void reset() {
        guardHttp(resetButton).click();
    }

    public void selectBaseType(String label) {
        selectRadioButton(baseType, label);
    }

    public void selectPackagingType(String label) {
        selectRadioButton(packagingType, label);
    }

    public void toggleFeature(String feature) {
        guardAjax(webDriver.findElement(By.id("starterForm:" + feature)).findElement(By.className("ui-chkbox-box"))).click();
    }

    private static void selectRadioButton(WebElement radioGroup, String label) {
        guardAjax(radioGroup.findElement(By.xpath(".//label[normalize-space() = '%s']".formatted(label)))).click();
    }
}

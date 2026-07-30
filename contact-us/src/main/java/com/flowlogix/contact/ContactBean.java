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
package com.flowlogix.contact;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.MailSessionDefinition;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Backing bean for the contact form.
 */
@Slf4j
@Named
@RequestScoped
@MailSessionDefinition(name = "java:app/contact/mail/HopeMail",
        transportProtocol = "smtp", storeProtocol = "imaps",
        properties = {
                "mail.smtp.auth=true", "mail.smtp.starttls.enable=true", "mail.debug=false"})
public class ContactBean {
    @Getter
    @Setter
    public static class ContactInfo {
        @NotBlank(message = "Name is required")
        private String name;

        @NotBlank(message = "Email is required")
        @Email(message = "Please enter a valid email address")
        private String email;

        private String company;
        private String phone;

        @NotEmpty(message = "Please select at least one product")
        private List<String> selectedProducts;
        private String message;
    }

    private static final List<String> PRODUCTS = List.of(
        "Jakarta EE migration",
        "Apache Shiro Security support",
        "Debugging enhanced by AI",
        "MirrorImmich",
        "Jakarta Persistence / ORM support",
        "Something else"
    );

    @Resource(name = "java:app/contact/mail/HopeMail")
    Session mailSession;
    @Inject
    @ConfigProperty(name = "hope-smtp-host", defaultValue = "none")
    private String smtpHost;
    @Inject
    @ConfigProperty(name = "hope-smtp-port", defaultValue = "587")
    private int smtpPort;
    @Inject
    @ConfigProperty(name = "hope-smtp-user", defaultValue = "none")
    private String smtpUser;
    @Inject
    @ConfigProperty(name = "hope-smtp-password", defaultValue = "none")
    private String smtpPassword;

    @Valid
    @Getter
    private ContactInfo contactInfo = new ContactInfo();

    public List<String> getProducts() {
        return PRODUCTS;
    }

    public void submit() {
        log.debug("Contact Form Submission: Name={}, Email={}, Company={}, Phone={}, Products={}, Message={}",
                contactInfo.getName(), contactInfo.getEmail(), contactInfo.getCompany(),
                contactInfo.getPhone(), contactInfo.getSelectedProducts(), contactInfo.getMessage());
        try {
            sendMessage(contactInfo);
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO,
                    "", "Thank you for contacting Flow Logix! We will get back to you soon."));
            clear();
        } catch (MessagingException e) {
            log.warn("Failed to send contact form email", e);
            FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error Submitting a form", "There was an error submitting the form. Please try again later."));
        }
    }

    public void clear() {
        contactInfo = new ContactInfo();
    }

    private void sendMessage(ContactInfo contactInfo) throws MessagingException {
        try (var transport = mailSession.getTransport()) {
            transport.connect(smtpHost, smtpPort, smtpUser, smtpPassword);
            var message = new MimeMessage(mailSession);
            message.setFrom("%s (Contact Form) <%s>".formatted(contactInfo.getName(), contactInfo.getEmail()));
            message.setRecipients(Message.RecipientType.TO, "info@flowlogix.com");
            message.setSubject("New Contact Form Submission from %s".formatted(contactInfo.getName()));
            var body = """
                A new contact form submission has been received:
                Name: %s
                Email: %s
                Company: %s
                Phone: %s
                Selected Products: %s
                Message:
                %s
                """.formatted(contactInfo.getName(), contactInfo.getEmail(),
                    contactInfo.getCompany() == null ? "N/A" : contactInfo.getCompany(),
                    contactInfo.getPhone() == null ? "N/A" : contactInfo.getPhone(),
                    String.join(", ", contactInfo.getSelectedProducts()),
                    contactInfo.getMessage() == null ? "N/A" : contactInfo.getMessage()
                );
            message.setText(body);
            transport.sendMessage(message, message.getAllRecipients());
        }
    }
}

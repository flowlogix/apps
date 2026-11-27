module HopeNYC {
    requires jakarta.mail;
    requires jakarta.ejb;
    requires jakarta.persistence;
    requires jakarta.validation;
    requires jakarta.faces;
    requires jakarta.servlet;
    requires java.compiler;
    requires static lombok;
    requires org.omnifaces;
    requires org.primefaces;
    requires microprofile.config.api;
    requires com.flowlogix.datamodel;
    requires com.flowlogix.jee;
    requires org.apache.commons.lang3;
    requires org.apache.shiro.core;
    requires org.apache.shiro.jakarta.ee;
    requires org.apache.shiro.cdi;
    requires org.apache.shiro.config.core;
    requires org.apache.shiro.crypto.cipher;
    requires org.apache.shiro.crypto.core;
    requires org.apache.shiro.lang;
    requires org.apache.shiro.web;
    requires libpam4j;
    requires org.slf4j;
}

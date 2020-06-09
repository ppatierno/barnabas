/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.keycloak;

import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.test.TestUtils;
import io.strimzi.test.executor.Exec;
import io.strimzi.test.executor.ExecResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KeycloakInstance {

    private static final Logger LOGGER = LogManager.getLogger(KeycloakInstance.class);

    private final int jwksExpireSeconds = 500;
    private final int jwksRefreshSeconds = 400;
    private final String username;
    private final String password;
    private final String httpsUri;
    private final String httpUri;

    private String validIssuerUri;
    private String jwksEndpointUri;
    private String oauthTokenEndpointUri;
    private String introspectionEndpointUri;
    private String userNameClaim;

    private Pattern keystorePattern = Pattern.compile("<tls>\\s*<key-stores>\\s*<key-store name=\"kcKeyStore\">\\s*<credential-reference clear-text=\".*\"\\/>");
    private Pattern keystorePasswordPattern = Pattern.compile("\\\".*\\\"");

    public KeycloakInstance(String username, String password) {

        this.username = username;
        this.password = password;
        this.httpsUri = ResourceManager.kubeClient().getNodeAddress() + ":" + Constants.HTTPS_KEYCLOAK_DEFAULT_NODE_PORT;
        this.httpUri = ResourceManager.kubeClient().getNodeAddress() + ":" + Constants.HTTP_KEYCLOAK_DEFAULT_NODE_PORT;
        this.validIssuerUri = "https://" + httpsUri + "/auth/realms/internal";
        this.jwksEndpointUri = "https://" + httpsUri + "/auth/realms/internal/protocol/openid-connect/certs";
        this.oauthTokenEndpointUri = "https://" + httpsUri + "/auth/realms/internal/protocol/openid-connect/token";
        this.introspectionEndpointUri = "https://" + httpsUri + "/auth/realms/internal/protocol/openid-connect/token/introspect";
        this.userNameClaim = "preferred_username";
    }

    public void importRealm(String pathToScript) {
        TestUtils.waitFor("Verify that kafka contains cruise control topics with related configuration.",
            Constants.GLOBAL_CLIENTS_POLL, Constants.GLOBAL_TIMEOUT, () -> {

                ExecResult result = Exec.exec(true, "/bin/bash", pathToScript, username, password, httpsUri);

                LOGGER.info("This is out: {}", result.out());
                LOGGER.info("This is err: {}", result.err());

                if (result.err().contains("HTTP/2 201") && result.out().isEmpty()) {
                    LOGGER.debug("Importing of realm succeed with code HTTP/2 201");
                    return true;
                }
                LOGGER.error("Importing of realm failed gonna try it again.");
                return false;
            });
    }

    public void setRealm(String realmName, boolean tlsEnabled) {
        LOGGER.info("Replacing validIssuerUri: {} to pointing to {} realm", validIssuerUri, realmName);
        LOGGER.info("Replacing jwksEndpointUri: {} to pointing to {} realm", jwksEndpointUri, realmName);
        LOGGER.info("Replacing oauthTokenEndpointUri: {} to pointing to {} realm", oauthTokenEndpointUri, realmName);

        if (tlsEnabled) {
            LOGGER.info("Using HTTPS endpoints");
            validIssuerUri = "https://" + httpsUri + "/auth/realms/" + realmName;
            jwksEndpointUri = "https://" + httpsUri + "/auth/realms/" + realmName + "/protocol/openid-connect/certs";
            oauthTokenEndpointUri = "https://" + httpsUri + "/auth/realms/" + realmName + "/protocol/openid-connect/token";

        } else {
            LOGGER.info("Using HTTP endpoints");
            validIssuerUri = "http://" + httpUri + "/auth/realms/" + realmName;
            jwksEndpointUri = "http://" + httpUri + "/auth/realms/" + realmName + "/protocol/openid-connect/certs";
            oauthTokenEndpointUri = "http://" + httpUri + "/auth/realms/" + realmName + "/protocol/openid-connect/token";
        }
    }

    public String getKeystorePassword() {
        String keycloakPodName = kubeClient().listPodsByPrefixInName("keycloak-0").get(0).getMetadata().getName();
        String inputFile = ResourceManager.cmdKubeClient().execInPod(keycloakPodName,
            "cat", "/opt/jboss/keycloak/standalone/configuration/standalone-ha.xml").out().trim();

        Matcher keystoreMatcher = keystorePattern.matcher(inputFile);
        String keystorePassword = null;

        if (keystoreMatcher.find()) {
            String result = keystoreMatcher.group(0);
            LOGGER.info(result);

            String[] shards = result.split("\n");
            LOGGER.info(shards[3]);

            Matcher keystorePasswordMatcher = keystorePasswordPattern.matcher(shards[3]);

            if (keystorePasswordMatcher.find()) {
                keystorePassword = keystorePasswordMatcher.group(0);
                // erasing the '"'
                keystorePassword = keystorePassword.substring(1, keystorePassword.length() - 1);
                LOGGER.info(keystorePassword);
            }
        }
        return keystorePassword;
    }

    public String getUsername() {
        return username;
    }
    public String getPassword() {
        return password;
    }
    public String getHttpsUri() {
        return httpsUri;
    }
    public String getHttpUri() {
        return httpUri;
    }
    public String getValidIssuerUri() {
        return validIssuerUri;
    }
    public void setValidIssuerUri(String validIssuerUri) {
        this.validIssuerUri = validIssuerUri;
    }
    public String getJwksEndpointUri() {
        return jwksEndpointUri;
    }
    public void setJwksEndpointUri(String jwksEndpointUri) {
        this.jwksEndpointUri = jwksEndpointUri;
    }
    public String getOauthTokenEndpointUri() {
        return oauthTokenEndpointUri;
    }
    public void setOauthTokenEndpointUri(String oauthTokenEndpointUri) {
        this.oauthTokenEndpointUri = oauthTokenEndpointUri;
    }
    public String getIntrospectionEndpointUri() {
        return introspectionEndpointUri;
    }
    public void setIntrospectionEndpointUri(String introspectionEndpointUri) {
        this.introspectionEndpointUri = introspectionEndpointUri;
    }
    public String getUserNameClaim() {
        return userNameClaim;
    }
    public void setUserNameClaim(String userNameClaim) {
        this.userNameClaim = userNameClaim;
    }
    public Pattern getKeystorePattern() {
        return keystorePattern;
    }
    public void setKeystorePattern(Pattern keystorePattern) {
        this.keystorePattern = keystorePattern;
    }

    public int getJwksExpireSeconds() {
        return jwksExpireSeconds;
    }
    public int getJwksRefreshSeconds() {
        return jwksRefreshSeconds;
    }

    @Override
    public String toString() {
        return "KeycloakInstance{" +
            "jwksExpireSeconds=" + jwksExpireSeconds +
            ", jwksRefreshSeconds=" + jwksRefreshSeconds +
            ", username='" + username + '\'' +
            ", password='" + password + '\'' +
            ", httpsUri='" + httpsUri + '\'' +
            ", httpUri='" + httpUri + '\'' +
            ", validIssuerUri='" + validIssuerUri + '\'' +
            ", jwksEndpointUri='" + jwksEndpointUri + '\'' +
            ", oauthTokenEndpointUri='" + oauthTokenEndpointUri + '\'' +
            ", introspectionEndpointUri='" + introspectionEndpointUri + '\'' +
            ", userNameClaim='" + userNameClaim + '\'' +
            ", keystorePattern=" + keystorePattern +
            ", keystorePasswordPattern=" + keystorePasswordPattern +
            '}';
    }
}

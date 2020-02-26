/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.listener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.GenericSecretSource;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.Minimum;
import io.sundr.builder.annotations.Buildable;
import io.vertx.core.cli.annotations.DefaultValue;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Configures a listener to use OAuth authentication.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class KafkaListenerAuthenticationOAuth extends KafkaListenerAuthentication {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_OAUTH = "oauth";
    public static final int DEFAULT_JWKS_EXPIRY_SECONDS = 360;
    public static final int DEFAULT_JWKS_REFRESH_SECONDS = 300;

    private String clientId;
    private GenericSecretSource clientSecret;
    private String validIssuerUri;
    private String jwksEndpointUri;
    private Integer jwksRefreshSeconds;
    private Integer jwksExpirySeconds;
    private String introspectionEndpointUri;
    private String userNameClaim;
    private boolean checkAccessTokenType = true;
    private boolean accessTokenIsJwt = true;
    private List<CertSecretSource> tlsTrustedCertificates;
    private boolean disableTlsHostnameVerification = false;
    private boolean enableECDSA = false;

    @Description("Must be `" + TYPE_OAUTH + "`")
    @Override
    public String getType() {
        return TYPE_OAUTH;
    }

    @Description("OAuth Client ID which the Kafka broker can use to authenticate against the authorization server and use the introspect endpoint URI.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Description("Link to Kubernetes Secret containing the OAuth client secret which the Kafka broker can use to authenticate against the authorization server and use the introspect endpoint URI.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public GenericSecretSource getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GenericSecretSource clientSecret) {
        this.clientSecret = clientSecret;
    }

    @Description("URI of the token issuer used for authentication.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getValidIssuerUri() {
        return validIssuerUri;
    }

    public void setValidIssuerUri(String validIssuerUri) {
        this.validIssuerUri = validIssuerUri;
    }

    @Description("URI of the JWKS certificate endpoint, which can be used for local JWT validation.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getJwksEndpointUri() {
        return jwksEndpointUri;
    }

    public void setJwksEndpointUri(String jwksEndpointUri) {
        this.jwksEndpointUri = jwksEndpointUri;
    }

    @Description("Configures how often are the JWKS certificates refreshed. " +
            "The refresh interval has to be at least 60 seconds shorter then the expiry interval specified in `jwksExpirySeconds`. " +
            "Defaults to 300 seconds.")
    @Minimum(1)
    @DefaultValue("300")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getJwksRefreshSeconds() {
        return jwksRefreshSeconds;
    }

    public void setJwksRefreshSeconds(Integer jwksRefreshSeconds) {
        this.jwksRefreshSeconds = jwksRefreshSeconds;
    }

    @Description("Configures how often are the JWKS certificates considered valid. " +
            "The expiry interval has to be at least 60 seconds longer then the refresh interval specified in `jwksRefreshSeconds`. " +
            "Defaults to 360 seconds.")
    @Minimum(1)
    @DefaultValue("360")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getJwksExpirySeconds() {
        return jwksExpirySeconds;
    }

    public void setJwksExpirySeconds(Integer jwksExpirySeconds) {
        this.jwksExpirySeconds = jwksExpirySeconds;
    }

    @Description("URI of the token introspection endpoint which can be used to validate opaque non-JWT tokens.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getIntrospectionEndpointUri() {
        return introspectionEndpointUri;
    }

    public void setIntrospectionEndpointUri(String introspectionEndpointUri) {
        this.introspectionEndpointUri = introspectionEndpointUri;
    }

    @Description("Name of the claim from the authentication token which will be used as the user principal. " +
            "Defaults to `sub`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getUserNameClaim() {
        return userNameClaim;
    }

    public void setUserNameClaim(String userNameClaim) {
        this.userNameClaim = userNameClaim;
    }

    @Description("Configure whether the access token type check should be performed or not. This should be set to `false` " +
            "if the authorization server does not include 'typ' claim in JWT token. Defaults to `true`.")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean isCheckAccessTokenType() {
        return checkAccessTokenType;
    }

    public void setCheckAccessTokenType(boolean checkAccessTokenType) {
        this.checkAccessTokenType = checkAccessTokenType;
    }

    @Description("Configure whether the access token should be treated as JWT. This should be set to `false` if " +
            "the authorization server returns opaque tokens. Defaults to `true`.")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean isAccessTokenIsJwt() {
        return accessTokenIsJwt;
    }

    public void setAccessTokenIsJwt(boolean accessTokenIsJwt) {
        this.accessTokenIsJwt = accessTokenIsJwt;
    }

    @Description("Trusted certificates for TLS connection to the OAuth server.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<CertSecretSource> getTlsTrustedCertificates() {
        return tlsTrustedCertificates;
    }

    public void setTlsTrustedCertificates(List<CertSecretSource> tlsTrustedCertificates) {
        this.tlsTrustedCertificates = tlsTrustedCertificates;
    }

    @Description("Enable or disable TLS hostname verification. " +
            "Default value is `false`.")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean isDisableTlsHostnameVerification() {
        return disableTlsHostnameVerification;
    }

    public void setDisableTlsHostnameVerification(boolean disableTlsHostnameVerification) {
        this.disableTlsHostnameVerification = disableTlsHostnameVerification;
    }

    @Description("Enable or disable ECDSA support by installing BouncyCastle crypto provider. " +
            "Default value is `false`.")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean isEnableECDSA() {
        return enableECDSA;
    }

    public void setEnableECDSA(boolean enableECDSA) {
        this.enableECDSA = enableECDSA;
    }
}

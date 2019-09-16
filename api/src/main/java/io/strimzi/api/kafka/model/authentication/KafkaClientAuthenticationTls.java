/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.authentication;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

/**
 * Configures the Kafka client authentication using TLS client authentication in client based components
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class KafkaClientAuthenticationTls extends KafkaClientAuthentication {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_TLS = "tls";

    private CertAndKeySecretSource certificateAndKey;

    @Description("Must be `" + TYPE_TLS + "`")
    @Override
    public String getType() {
        return TYPE_TLS;
    }

    @Description("Reference to the `Secret` which holds the certificate and private key pair.")
    public CertAndKeySecretSource getCertificateAndKey() {
        return certificateAndKey;
    }

    public void setCertificateAndKey(CertAndKeySecretSource certificateAndKey) {
        this.certificateAndKey = certificateAndKey;
    }
}

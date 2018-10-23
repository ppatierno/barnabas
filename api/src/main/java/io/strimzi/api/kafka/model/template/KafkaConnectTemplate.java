/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;

import java.io.Serializable;

/**
 * Representation of a template for Kafka Connect and Kafka Connect S2I resources.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "deployment", "pod", "apiService"})
public class KafkaConnectTemplate implements Serializable {
    private static final long serialVersionUID = 1L;


    private ResourceTemplate deployment;
    private ResourceTemplate pod;
    private ResourceTemplate apiService;

    @Description("Template for Kafka Connect `Deployment`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ResourceTemplate getDeployment() {
        return deployment;
    }

    public void setDeployment(ResourceTemplate deployment) {
        this.deployment = deployment;
    }

    @Description("Template for Kafka Connect `Pods`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ResourceTemplate getPod() {
        return pod;
    }

    public void setPod(ResourceTemplate pod) {
        this.pod = pod;
    }

    @Description("Template for Kafka Connect API `Service`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ResourceTemplate getApiService() {
        return apiService;
    }

    public void setApiService(ResourceTemplate apiService) {
        this.apiService = apiService;
    }
}

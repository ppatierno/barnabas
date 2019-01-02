/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.template;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.KubeLink;
import io.strimzi.crdgenerator.annotations.Minimum;
import io.sundr.builder.annotations.Buildable;
import io.vertx.core.cli.annotations.DefaultValue;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representation of a pod template for Strimzi resources.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "metadata", "imagePullSecrets", "securityContext", "terminationGracePeriodSeconds"})
public class PodTemplate implements Serializable {
    private static final long serialVersionUID = 1L;

    private MetadataTemplate metadata;
    private List<LocalObjectReference> imagePullSecrets;
    private PodSecurityContext securityContext;
    private int terminationGracePeriodSeconds = 30;
    private Map<String, Object> additionalProperties = new HashMap<>(0);

    @Description("Metadata which should be applied to the resource.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public MetadataTemplate getMetadata() {
        return metadata;
    }

    public void setMetadata(MetadataTemplate metadata) {
        this.metadata = metadata;
    }

    @Description("Configures pod-level security attributes and common container settings.")
    @KubeLink(group = "core", version = "v1", kind = "podsecuritycontext")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public PodSecurityContext getSecurityContext() {
        return securityContext;
    }

    public void setSecurityContext(PodSecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    @Description("List of references to secrets in the same namespace to use for pulling any of the images used by this Pod.")
    @KubeLink(group = "core", version = "v1", kind = "localobjectreference")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<LocalObjectReference> getImagePullSecrets() {
        return imagePullSecrets;
    }

    public void setImagePullSecrets(List<LocalObjectReference> imagePullSecrets) {
        this.imagePullSecrets = imagePullSecrets;
    }

    @Description("The grace period is the duration in seconds after the processes running in the pod are sent a termination signal and the time when the processes are forcibly halted with a kill signal. " +
            "Set this value longer than the expected cleanup time for your process." +
            "Value must be non-negative integer. " +
            "The value zero indicates delete immediately. " +
            "Defaults to 30 seconds.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @DefaultValue("30")
    @Minimum(0)
    public int getTerminationGracePeriodSeconds() {
        return terminationGracePeriodSeconds;
    }

    public void setTerminationGracePeriodSeconds(int terminationGracePeriodSeconds) {
        this.terminationGracePeriodSeconds = terminationGracePeriodSeconds;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}

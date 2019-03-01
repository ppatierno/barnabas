/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Representation for resource constraints.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class Resources implements UnknownPropertyPreserving, Serializable {

    private static final long serialVersionUID = 1L;

    private Map<String, Object> additionalProperties = new HashMap<>(0);

    public Resources() {
    }

    public Resources(CpuMemory limits, CpuMemory requests) {
        this.limits = limits;
        this.requests = requests;
    }

    private CpuMemory limits;

    private CpuMemory requests;

    @Description("Resource limits applied at runtime.")
    public CpuMemory getLimits() {
        return limits;
    }

    public void setLimits(CpuMemory limits) {
        this.limits = limits;
    }

    @Description("Resource requests applied during pod scheduling.")
    public CpuMemory getRequests() {
        return requests;
    }

    public void setRequests(CpuMemory requests) {
        this.requests = requests;
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

}

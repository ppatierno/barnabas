/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import io.strimzi.crdgenerator.annotations.Description;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;

/**
 * A representation of a group resource for ACLs
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AclRuleClusterResource extends AclRuleResource {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_CLUSTER = "cluster";

    @Description("Must be `" + TYPE_CLUSTER + "`")
    @Override
    public String getType() {
        return TYPE_CLUSTER;
    }
}
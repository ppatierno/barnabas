/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.Minimum;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Representation for persistent claim-based storage.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonPropertyOrder({"type", "size", "storageClass", "selector", "deleteClaim", "subPath"})
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class PersistentClaimStorage extends SingleVolumeStorage {

    private static final long serialVersionUID = 1L;

    private String size;
    private String storageClass;
    private Map<String, String> selector;
    private boolean deleteClaim;
    private String subPath;

    private Integer id;

    @Description("Must be `" + TYPE_PERSISTENT_CLAIM + "`")
    @Override
    public String getType() {
        return TYPE_PERSISTENT_CLAIM;
    }

    @Override
    @Description("Storage identification number. It is mandatory only for storage volumes defined in a storage of type 'jbod'")
    @Minimum(0)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getId() {
        return super.getId();
    }

    @Override
    public void setId(Integer id) {
        super.setId(id);
    }

    @Description("When type=persistent-claim, defines the size of the persistent volume claim (i.e 1Gi). " +
            "Mandatory when type=persistent-claim.")
    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    @JsonProperty("class")
    @Description("The storage class to use for dynamic volume allocation.")
    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    @Description("Specifies a specific persistent volume to use. " +
            "It contains key:value pairs representing labels for selecting such a volume.")
    public Map<String, String> getSelector() {
        return selector;
    }

    public void setSelector(Map<String, String> selector) {
        this.selector = selector;
    }

    @Description("Specifies if the persistent volume claim has to be deleted when the cluster is un-deployed.")
    @JsonProperty(defaultValue = "false")
    public boolean isDeleteClaim() {
        return deleteClaim;
    }

    public void setDeleteClaim(boolean deleteClaim) {
        this.deleteClaim = deleteClaim;
    }

    @Description("The subPath on the claimed volume to use")
    @JsonProperty("subPath")
    public String getSubPath() {
        return subPath;
    }

    public void setSubPath(String subPath) {
        this.subPath = subPath;
    }

    /**
     * {@inheritDoc}
     */
    public String invalidityReason() {
        return size == null || size.isEmpty()
                ? "The size is mandatory for a persistent-claim storage"
                : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsPersistentStorage() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iteratePersistentClaimStorage(BiConsumer<PersistentClaimStorage, String> consumer, String name) {
        consumer.accept(this, name);
    }
}

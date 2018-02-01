package io.strimzi.controller.cluster.operations.openshift;

import io.strimzi.controller.cluster.OpenShiftUtils;
import io.strimzi.controller.cluster.operations.Operation;
import io.strimzi.controller.cluster.resources.Source2Image;

/**
 * Base Source2Image operation
 */
public abstract class S2IOperation implements Operation<OpenShiftUtils> {
    protected final Source2Image s2i;

    /**
     * Constructor
     *
     * @param s2i   Source2Image instance
     */
    protected S2IOperation(Source2Image s2i) {
        this.s2i = s2i;
    }
}

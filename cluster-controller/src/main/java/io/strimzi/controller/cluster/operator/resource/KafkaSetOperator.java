/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialization of {@link StatefulSetOperator} for StatefulSets of Kafka brokers
 */
public class KafkaSetOperator extends StatefulSetOperator<Boolean> {

    private static final Logger log = LoggerFactory.getLogger(KafkaSetOperator.class);

    /**
     * Constructor
     *
     * @param vertx  The Vertx instance
     * @param client The Kubernetes client
     */
    public KafkaSetOperator(Vertx vertx, KubernetesClient client) {
        super(vertx, client);
    }

    @Override
    protected Future<ReconcileResult<Boolean>> internalPatch(String namespace, String name, StatefulSet current, StatefulSet desired) {
        StatefulSetDiff diff = new StatefulSetDiff(current, desired);
        if (diff.changesVolumeClaimTemplates() || diff.changesSpecTemplateSpecInitContainers()) {
            log.warn("Changing Kafka storage type or size is not possible. The changes will be ignored.");
            diff = revertStorageChanges(current, desired);
        }
        if (diff.isEmpty()) {
            return Future.succeededFuture(ReconcileResult.noop());
        } else {
            boolean different = needsRollingUpdate(diff);
            return super.internalPatch(namespace, name, current, desired).map(r -> {
                if (r instanceof ReconcileResult.Patched) {
                    return ReconcileResult.patched(different);
                } else {
                    return r;
                }
            });
        }
    }

    public static boolean needsRollingUpdate(StatefulSetDiff diff) {
        if (diff.changesLabels()) {
            log.debug("Changed labels => needs rolling update");
            return true;
        }
        if (diff.changesSpecTemplateSpec()) {
            log.debug("Changed template spec => needs rolling update");
            return true;
        }
        return false;
    }
}

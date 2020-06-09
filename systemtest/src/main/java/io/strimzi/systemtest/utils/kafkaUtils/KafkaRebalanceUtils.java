/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kafkaUtils;

import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.crd.KafkaRebalanceResource;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KafkaRebalanceUtils {

    private static final Logger LOGGER = LogManager.getLogger(KafkaRebalanceUtils.class);

    private KafkaRebalanceUtils() {}

    public enum KafkaRebalanceState {
        PendingProposal,
        ProposalReady,
        Rebalancing,
        Ready,
        NotReady,
        Stopped
    }

    public static void waitForKafkaRebalanceCustomResourceState(String resourceName, KafkaRebalanceState state) {
        LOGGER.info("Waiting for KafkaRebalance will be in the {}", state);

        TestUtils.waitFor("Waiting for KafkaRebalance will be in the " + state.name(), Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> {
                LOGGER.info("Comparing: " + KafkaRebalanceResource.kafkaRebalanceClient().inNamespace(kubeClient().getNamespace())
                    .withName(resourceName).get().getStatus().getConditions().get(0).getStatus() + " - " + state.name());
                return KafkaRebalanceResource.kafkaRebalanceClient().inNamespace(kubeClient().getNamespace())
                    .withName(resourceName).get().getStatus().getConditions().get(0).getStatus().equals(state.name());
            },
            () -> LOGGER.info(KafkaRebalanceResource.kafkaRebalanceClient().inNamespace(kubeClient().getNamespace()).withName(resourceName).get())
        );
    }

}

/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kafkaUtils;

import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.crd.KafkaConnectS2IResource;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KafkaConnectS2IUtils {

    private static final Logger LOGGER = LogManager.getLogger(KafkaConnectS2IUtils.class);

    private KafkaConnectS2IUtils() {}

    /**
     * Wait until the given Kafka ConnectS2I cluster is in desired state.
     * @param clusterName The name of the Kafka ConnectS2I cluster.
     * @param status desired status value
     */
    public static void waitForConnectS2IStatus(String clusterName, String status) {
        LOGGER.info("Wait until KafkaConnectS2I {} will be in state: {}", clusterName, status);
        TestUtils.waitFor("KafkaConnectS2I " + clusterName + " state: " + status, Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> KafkaConnectS2IResource.kafkaConnectS2IClient().inNamespace(kubeClient().getNamespace())
                    .withName(clusterName).get().getStatus().getConditions().get(0).getType().equals(status),
            () -> StUtils.logCurrentStatus(KafkaConnectS2IResource.kafkaConnectS2IClient().inNamespace(kubeClient().getNamespace()).withName(clusterName).get()));
        LOGGER.info("KafkaConnectS2I {} is in desired state: {}", clusterName, status);
    }

    public static void waitForConnectS2IReady(String clusterName) {
        waitForConnectS2IStatus(clusterName, "Ready");
    }

    public static void waitForConnectS2INotReady(String clusterName) {
        waitForConnectS2IStatus(clusterName, "NotReady");
    }
}

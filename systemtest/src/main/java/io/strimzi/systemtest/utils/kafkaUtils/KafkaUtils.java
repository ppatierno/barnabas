/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kafkaUtils;

import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.ListenerStatus;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.exceptions.KubeClusterException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.test.TestUtils.indent;
import static io.strimzi.test.TestUtils.waitFor;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KafkaUtils {

    private static final Logger LOGGER = LogManager.getLogger(KafkaUtils.class);

    private KafkaUtils() {}

    public static void waitUntilKafkaCRIsReady(String clusterName) {
        waitUntilKafkaStatus(clusterName, "Ready");
    }

    public static void waitUntilKafkaCRIsNotReady(String clusterName) {
        waitUntilKafkaStatus(clusterName, "NotReady");
    }

    public static void waitUntilKafkaStatus(String clusterName, String state) {
        LOGGER.info("Waiting till Kafka CR will be in state: {}", state);
        TestUtils.waitFor("Waiting for Kafka resource status is: " + state, Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT, () -> {
            List<Condition> conditions =
                    Crds.kafkaOperation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).withName(clusterName)
                            .get().getStatus().getConditions().stream().filter(condition -> !condition.getType().equals("Warning"))
                            .collect(Collectors.toList());

            for (Condition condition : conditions) {
                if (!condition.getType().matches(state))
                    return false;
            }

            return true;
        });
        LOGGER.info("Kafka CR is in state: {}", state);
    }

    public static void waitUntilKafkaStatusConditionContainsMessage(String clusterName, String namespace, String message) {
        TestUtils.waitFor("Kafka status contains exception with non-existing secret name",
            Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT, () -> {
                List<Condition> conditions = KafkaResource.kafkaClient().inNamespace(namespace).withName(clusterName).get().getStatus().getConditions();
                for (Condition condition : conditions) {
                    if (condition.getMessage().matches(message)) {
                        return true;
                    }
                }
                return false;
            });
    }

    public static void waitForZkMntr(String clusterName, Pattern pattern, int... podIndexes) {
        long timeoutMs = 120_000L;
        long pollMs = 1_000L;

        for (int podIndex : podIndexes) {
            String zookeeperPod = KafkaResources.zookeeperPodName(clusterName, podIndex);
            String zookeeperPort = String.valueOf(2181 * 10 + podIndex);
            waitFor("mntr", pollMs, timeoutMs, () -> {
                    try {
                        String output = cmdKubeClient().execInPod(zookeeperPod,
                            "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out();

                        if (pattern.matcher(output).find()) {
                            return true;
                        }
                    } catch (KubeClusterException e) {
                        LOGGER.trace("Exception while waiting for ZK to become leader/follower, ignoring", e);
                    }
                    return false;
                },
                () -> LOGGER.info("zookeeper `mntr` output at the point of timeout does not match {}:{}{}",
                    pattern.pattern(),
                    System.lineSeparator(),
                    indent(cmdKubeClient().execInPod(zookeeperPod, "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out()))
            );
        }
    }

    public static String getKafkaStatusCertificates(String listenerType, String namespace, String clusterName) {
        String certs = "";
        List<ListenerStatus> kafkaListeners = KafkaResource.kafkaClient().inNamespace(namespace).withName(clusterName).get().getStatus().getListeners();

        for (ListenerStatus listener : kafkaListeners) {
            if (listener.getType().equals(listenerType))
                certs = listener.getCertificates().toString();
        }
        certs = certs.substring(1, certs.length() - 1);
        return certs;
    }

    public static String getKafkaSecretCertificates(String secretName, String certType) {
        String secretCerts = "";
        secretCerts = kubeClient().getSecret(secretName).getData().get(certType);
        byte[] decodedBytes = Base64.getDecoder().decode(secretCerts);
        secretCerts = new String(decodedBytes, Charset.defaultCharset());

        return secretCerts;
    }
}

/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.cli;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;

import java.util.Arrays;
import java.util.List;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KafkaCmdClient {

    private static final int PORT = 12181;

    public KafkaCmdClient() { }

    public static List<String> listTopicsUsingPodCli(String clusterName, int zkPodId) {
        String podName = KafkaResources.zookeeperPodName(clusterName, zkPodId);
        return Arrays.asList(cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
            "bin/kafka-topics.sh --list --zookeeper localhost:" + PORT).out().split("\\s+"));
    }

    public static String createTopicUsingPodCli(String clusterName, int zkPodId, String topic, int replicationFactor, int partitions) {
        String podName = KafkaResources.zookeeperPodName(clusterName, zkPodId);
        String response = cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
            "bin/kafka-topics.sh --zookeeper localhost:" + PORT + " --create " + " --topic " + topic +
                " --replication-factor " + replicationFactor + " --partitions " + partitions).out();

        KafkaTopicUtils.waitForKafkaTopicCreation(topic);

        KafkaTopic kafkaTopic = KafkaTopicResource.kafkaTopicClient().inNamespace(kubeClient().getNamespace()).withName(topic).get();
        ResourceManager.getPointerResources().push(() -> ResourceManager.deleteLater(KafkaTopicResource.kafkaTopicClient(), kafkaTopic));

        return response;
    }

    public static String deleteTopicUsingPodCli(String clusterName, int zkPodId, String topic) {
        String podName = KafkaResources.zookeeperPodName(clusterName, zkPodId);
        return cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
            "bin/kafka-topics.sh --zookeeper localhost:" + PORT + " --delete --topic " + topic).out();
    }

    public static List<String> describeTopicUsingPodCli(String clusterName, int zkPodId, String topic) {
        String podName = KafkaResources.zookeeperPodName(clusterName, zkPodId);
        return Arrays.asList(cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
            "bin/kafka-topics.sh --zookeeper localhost:" + PORT + " --describe --topic " + topic).out().replace(": ", ":").split("\\s+"));
    }

    public static String updateTopicPartitionsCountUsingPodCli(String clusterName, int zkPodId, String topic, int partitions) {
        String podName = KafkaResources.zookeeperPodName(clusterName, zkPodId);
        return cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
            "bin/kafka-topics.sh --zookeeper localhost:" + PORT + " --alter --topic " + topic + " --partitions " + partitions).out();
    }
}

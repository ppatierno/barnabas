/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;

import java.io.File;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public abstract class AbstractNamespaceST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(AbstractNamespaceST.class);

    static final String CO_NAMESPACE = "co-namespace-test";
    static final String SECOND_NAMESPACE = "second-namespace-test";
    static final String TOPIC_NAME = "my-topic";
    static final String USER_NAME = "my-user";
    private static final String TOPIC_EXAMPLES_DIR = "../examples/topic/kafka-topic.yaml";

    static Resources secondNamespaceResources;

    void checkKafkaInDiffNamespaceThanCO(String clusterName, String namespace) {
        String previousNamespace = setNamespace(namespace);
        LOGGER.info("Check if Kafka Cluster {} in namespace {}", KafkaResources.kafkaStatefulSetName(clusterName), namespace);

        TestUtils.waitFor("Kafka Cluster status is not in desired state: Ready", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT, () -> {
            Condition kafkaCondition = secondNamespaceResources.kafka().inNamespace(namespace).withName(clusterName).get()
                    .getStatus().getConditions().get(0);
            LOGGER.info("Kafka condition status: {}", kafkaCondition.getStatus());
            LOGGER.info("Kafka condition type: {}", kafkaCondition.getType());
            return kafkaCondition.getType().equals("Ready");
        });

        Condition kafkaCondition = secondNamespaceResources.kafka().inNamespace(namespace).withName(clusterName).get()
                .getStatus().getConditions().get(0);

        assertThat(kafkaCondition.getType(), is("Ready"));
        setNamespace(previousNamespace);
    }

    void checkMirrorMakerForKafkaInDifNamespaceThanCO(String sourceClusterName) {
        String kafkaSourceName = sourceClusterName;
        String kafkaTargetName = CLUSTER_NAME + "-target";

        String previousNamespace = setNamespace(SECOND_NAMESPACE);
        secondNamespaceResources.kafkaEphemeral(kafkaTargetName, 1, 1).done();
        secondNamespaceResources.kafkaMirrorMaker(CLUSTER_NAME, kafkaSourceName, kafkaTargetName, "my-group", 1, false).done();

        LOGGER.info("Waiting for creation {} in namespace {}", CLUSTER_NAME + "-mirror-maker", SECOND_NAMESPACE);
        StUtils.waitForDeploymentReady(CLUSTER_NAME + "-mirror-maker", 1);
        setNamespace(previousNamespace);
    }

    void deployNewTopic(String topicNamespace, String kafkaClusterNamespace, String topic) {
        LOGGER.info("Creating topic {} in namespace {}", topic, topicNamespace);
        setNamespace(topicNamespace);
        cmdKubeClient().create(new File(TOPIC_EXAMPLES_DIR));
        TestUtils.waitFor("wait for 'my-topic' to be created in Kafka", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_TOPIC_CREATION, () -> {
            setNamespace(kafkaClusterNamespace);
            List<String> topics2 = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
            return topics2.contains(topic);
        });
    }

    void deleteNewTopic(String namespace, String topic) {
        LOGGER.info("Deleting topic {} in namespace {}", topic, namespace);
        setNamespace(namespace);
        cmdKubeClient().deleteByName("KafkaTopic", topic);
        setNamespace(CO_NAMESPACE);
    }

    @AfterAll
    void teardownAdditionalResources() {
        secondNamespaceResources.deleteResources();
    }
}

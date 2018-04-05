/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.test.ClusterController;
import io.strimzi.test.EnvVariables;
import io.strimzi.test.KafkaCluster;
import io.strimzi.test.Namespace;
import io.strimzi.test.StrimziRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.strimzi.test.k8s.BaseKubeClient.CM;
import static io.strimzi.test.k8s.BaseKubeClient.DEPLOYMENT;
import static io.strimzi.test.k8s.BaseKubeClient.SERVICE;
import static io.strimzi.test.k8s.BaseKubeClient.STATEFUL_SET;

@RunWith(StrimziRunner.class)
@Namespace(RecoveryClusterTest.NAMESPACE)
@ClusterController(envVariables = {@EnvVariables(key = "STRIMZI_FULL_RECONCILIATION_INTERVAL_MS", value = "30000")})
public class RecoveryClusterTest extends AbstractClusterTest {

    static final String NAMESPACE = "recovery-cluster-test";
    private static final String CLUSTER_NAME = "recovery-cluster";

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryClusterTest.class);

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteTopicControllerDeployment() {
        // kafka cluster already deployed via annotation
        String topicControllerDeploymentName = topicControllerDeploymentName(CLUSTER_NAME);
        LOGGER.info("Running deleteTopicControllerDeployment with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(DEPLOYMENT, topicControllerDeploymentName);
        kubeClient.waitForResourceDeletion(DEPLOYMENT, topicControllerDeploymentName);

        LOGGER.info("Waiting for recovery {}", topicControllerDeploymentName);
        kubeClient.waitForDeployment(topicControllerDeploymentName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteKafkaStatefulSet() {
        // kafka cluster already deployed via annotation
        String kafkaStatefulSetName = kafkaClusterName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaStatefulSet with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(STATEFUL_SET, kafkaStatefulSetName);
        kubeClient.waitForResourceDeletion(STATEFUL_SET, kafkaStatefulSetName);

        LOGGER.info("Waiting for recovery {}", kafkaStatefulSetName);
        kubeClient.waitForStatefulSet(kafkaStatefulSetName, 1);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1, zkNodes = 1)
    public void testDeleteZookeeperStatefulSet() {
        // kafka cluster already deployed via annotation
        String zookeeperStatefulSetName = zookeeperClusterName(CLUSTER_NAME);

        LOGGER.info("Running deleteZookeeperStatefulSet with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(STATEFUL_SET, zookeeperStatefulSetName);
        kubeClient.waitForResourceDeletion(STATEFUL_SET, zookeeperStatefulSetName);

        LOGGER.info("Waiting for recovery {}", zookeeperStatefulSetName);
        kubeClient.waitForStatefulSet(zookeeperStatefulSetName, 1);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteKafkaService() {
        // kafka cluster already deployed via annotation
        String kafkaServiceName = kafkaClusterName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaService with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(SERVICE, kafkaServiceName);
        kubeClient.waitForResourceDeletion(SERVICE, kafkaServiceName);

        LOGGER.info("Waiting for creation {}", kafkaServiceName);
        kubeClient.waitForResourceCreation(SERVICE, kafkaServiceName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteZookeeperService() {
        // kafka cluster already deployed via annotation
        String zookeeperServiceName = zookeeperClusterName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaService with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(SERVICE, zookeeperServiceName);
        kubeClient.waitForResourceDeletion(SERVICE, zookeeperServiceName);

        LOGGER.info("Waiting for creation {}", zookeeperServiceName);
        kubeClient.waitForResourceCreation(SERVICE, zookeeperServiceName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteKafkaHeadlessService() {
        // kafka cluster already deployed via annotation
        String kafkaHeadlessServiceName = kafkaHeadlessServiceName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaHeadlessService with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(SERVICE, kafkaHeadlessServiceName);
        kubeClient.waitForResourceDeletion(SERVICE, kafkaHeadlessServiceName);

        LOGGER.info("Waiting for creation {}", kafkaHeadlessServiceName);
        kubeClient.waitForResourceCreation(SERVICE, kafkaHeadlessServiceName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteZookeeperHeadlessService() {
        // kafka cluster already deployed via annotation
        String zookeeperHeadlessServiceName = zookeeperHeadlessServiceName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaHeadlessService with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(SERVICE, zookeeperHeadlessServiceName);
        kubeClient.waitForResourceDeletion(SERVICE, zookeeperHeadlessServiceName);

        LOGGER.info("Waiting for creation {}", zookeeperHeadlessServiceName);
        kubeClient.waitForResourceCreation(SERVICE, zookeeperHeadlessServiceName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteKafkaMetricsConfig() {
        // kafka cluster already deployed via annotation
        String kafkaMetricsConfigName = kafkaMetricsConfigName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaMetricsConfig with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(CM, kafkaMetricsConfigName);
        kubeClient.waitForResourceDeletion(CM, kafkaMetricsConfigName);

        LOGGER.info("Waiting for creation {}", kafkaMetricsConfigName);
        kubeClient.waitForResourceCreation(CM, kafkaMetricsConfigName);
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1)
    public void testDeleteZookeeperMetricsConfig() {
        // kafka cluster already deployed via annotation
        String zookeeperMetricsConfigName = zookeeperMetricsConfigName(CLUSTER_NAME);

        LOGGER.info("Running deleteZookeeperMetricsConfig with cluster {}", CLUSTER_NAME);

        kubeClient.deleteByName(CM, zookeeperMetricsConfigName);
        kubeClient.waitForResourceDeletion(CM, zookeeperMetricsConfigName);

        LOGGER.info("Waiting for creation {}", zookeeperMetricsConfigName);
        kubeClient.waitForResourceCreation(CM, zookeeperMetricsConfigName);
    }
}

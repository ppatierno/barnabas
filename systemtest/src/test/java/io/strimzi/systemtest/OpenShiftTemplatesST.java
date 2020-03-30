/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.KafkaConnectS2IList;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.DoneableKafkaConnectS2I;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.cmdClient.Oc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.TestUtils.map;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Basic tests for the OpenShift templates.
 * This only tests that the template create the appropriate resource,
 * not that the created resource is processed by operator(s) in the appropriate way.
 */
@OpenShiftOnly
@Tag(REGRESSION)
@Tag(ACCEPTANCE)
public class OpenShiftTemplatesST extends BaseST {

    private static final Logger LOGGER = LogManager.getLogger(OpenShiftTemplatesST.class);

    public static final String NAMESPACE = "template-test";
    private Oc oc = (Oc) cmdKubeClient(NAMESPACE);

    public Kafka getKafka(String clusterName) {
        return kubeClient().getClient().customResources(Crds.kafka(), Kafka.class, KafkaList.class, DoneableKafka.class).inNamespace(NAMESPACE).withName(clusterName).get();
    }

    public KafkaConnect getKafkaConnect(String clusterName) {
        return kubeClient().getClient().customResources(Crds.kafkaConnect(), KafkaConnect.class, KafkaConnectList.class, DoneableKafkaConnect.class).inNamespace(NAMESPACE).withName(clusterName).get();
    }

    public KafkaConnectS2I getKafkaConnectS2I(String clusterName) {
        return kubeClient().getClient().customResources(Crds.kafkaConnectS2I(), KafkaConnectS2I.class, KafkaConnectS2IList.class, DoneableKafkaConnectS2I.class).inNamespace(NAMESPACE).withName(clusterName).get();
    }

    @Test
    void testStrimziEphemeral() {
        String clusterName = "foo";
        oc.newApp("strimzi-ephemeral", map("CLUSTER_NAME", clusterName,
                "ZOOKEEPER_NODE_COUNT", "1",
                "KAFKA_NODE_COUNT", "1"));

        Kafka kafka = getKafka(clusterName);
        assertThat(kafka, is(notNullValue()));

        assertThat(kafka.getSpec().getKafka().getReplicas(), is(1));
        assertThat(kafka.getSpec().getZookeeper().getReplicas(), is(1));
        assertThat(kafka.getSpec().getKafka().getStorage().getType(), is("ephemeral"));
        assertThat(kafka.getSpec().getZookeeper().getStorage().getType(), is("ephemeral"));
    }

    @Test
    void testStrimziPersistent() {
        String clusterName = "bar";
        oc.newApp("strimzi-persistent", map("CLUSTER_NAME", clusterName,
                "ZOOKEEPER_NODE_COUNT", "1",
                "KAFKA_NODE_COUNT", "1"));

        Kafka kafka = getKafka(clusterName);
        assertThat(kafka, is(notNullValue()));
        assertThat(kafka.getSpec().getKafka().getReplicas(), is(1));
        assertThat(kafka.getSpec().getZookeeper().getReplicas(), is(1));
        assertThat(kafka.getSpec().getKafka().getStorage().getType(), is("jbod"));
        assertThat(kafka.getSpec().getZookeeper().getStorage().getType(), is("persistent-claim"));
    }

    @Test
    void testStrimziEphemeralWithCustomParameters() {
        String clusterName = "test-ephemeral-with-custom-parameters";
        oc.newApp("strimzi-ephemeral", map("CLUSTER_NAME", clusterName,
                "ZOOKEEPER_HEALTHCHECK_DELAY", "30",
                "ZOOKEEPER_HEALTHCHECK_TIMEOUT", "10",
                "KAFKA_HEALTHCHECK_DELAY", "30",
                "KAFKA_HEALTHCHECK_TIMEOUT", "10",
                "KAFKA_DEFAULT_REPLICATION_FACTOR", "2",
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "5",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "5"));

        //TODO Add assertions to check that Kafka brokers have a custom configuration
        Kafka kafka = getKafka(clusterName);
        assertThat(kafka, is(notNullValue()));

        assertThat(kafka.getSpec().getZookeeper().getLivenessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getZookeeper().getReadinessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getZookeeper().getLivenessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getZookeeper().getReadinessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getLivenessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getKafka().getReadinessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getKafka().getLivenessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getReadinessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getConfig().get("default.replication.factor"), is("2"));
        assertThat(kafka.getSpec().getKafka().getConfig().get("offsets.topic.replication.factor"), is("5"));
        assertThat(kafka.getSpec().getKafka().getConfig().get("transaction.state.log.replication.factor"), is("5"));
    }

    @Test
    void testStrimziPersistentWithCustomParameters() {
        String clusterName = "test-persistent-with-custom-parameters";
        oc.newApp("strimzi-persistent", map("CLUSTER_NAME", clusterName,
                "ZOOKEEPER_HEALTHCHECK_DELAY", "30",
                "ZOOKEEPER_HEALTHCHECK_TIMEOUT", "10",
                "KAFKA_HEALTHCHECK_DELAY", "30",
                "KAFKA_HEALTHCHECK_TIMEOUT", "10",
                "KAFKA_DEFAULT_REPLICATION_FACTOR", "2",
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "5",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "5",
                "ZOOKEEPER_VOLUME_CAPACITY", "2Gi",
                "KAFKA_VOLUME_CAPACITY", "2Gi"));

        //TODO Add assertions to check that Kafka brokers have a custom configuration
        Kafka kafka = getKafka(clusterName);
        assertThat(kafka, is(notNullValue()));

        assertThat(kafka.getSpec().getZookeeper().getLivenessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getZookeeper().getReadinessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getZookeeper().getLivenessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getZookeeper().getReadinessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getLivenessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getKafka().getReadinessProbe().getInitialDelaySeconds(), is(30));
        assertThat(kafka.getSpec().getKafka().getLivenessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getReadinessProbe().getTimeoutSeconds(), is(10));
        assertThat(kafka.getSpec().getKafka().getConfig().get("default.replication.factor"), is("2"));
        assertThat(kafka.getSpec().getKafka().getConfig().get("offsets.topic.replication.factor"), is("5"));
        assertThat(kafka.getSpec().getKafka().getConfig().get("transaction.state.log.replication.factor"), is("5"));
        assertThat(((PersistentClaimStorage) ((JbodStorage) kafka.getSpec().getKafka().getStorage()).getVolumes().get(0)).getSize(), is("2Gi"));
        assertThat(((PersistentClaimStorage) kafka.getSpec().getZookeeper().getStorage()).getSize(), is("2Gi"));
    }

    @Test
    void testConnect() {
        String clusterName = "test-connect";
        oc.newApp("strimzi-connect", map("CLUSTER_NAME", clusterName,
                "INSTANCES", "1"));

        KafkaConnect connect = getKafkaConnect(clusterName);
        assertThat(connect, is(notNullValue()));
        assertThat(connect.getSpec().getReplicas(), is(1));
    }

    @Test
    void testS2i() {
        String clusterName = "test-s2i";
        oc.newApp("strimzi-connect-s2i", map("CLUSTER_NAME", clusterName,
                "INSTANCES", "1"));

        KafkaConnectS2I cm = getKafkaConnectS2I(clusterName);
        assertThat(cm, is(notNullValue()));
        assertThat(cm.getSpec().getReplicas(), is(1));
    }

    @Test
    void testTopicOperator() {
        String topicName = "test-topic-topic";
        oc.newApp("strimzi-topic", map(
                "TOPIC_NAME", topicName,
                "TOPIC_PARTITIONS", "10",
                "TOPIC_REPLICAS", "2"));

        KafkaTopic topic = kubeClient().getClient().customResources(Crds.topic(), KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class).inNamespace(NAMESPACE).withName(topicName).get();
        assertThat(topic, is(notNullValue()));
        assertThat(topic.getSpec(), is(notNullValue()));
        assertThat(topic.getSpec().getTopicName(), is(nullValue()));
        assertThat(topic.getSpec().getPartitions(), is(10));
        assertThat(topic.getSpec().getReplicas(), is(2));
    }

    @BeforeAll
    void setup() {
        LOGGER.info("Creating resources before the test class");
        cluster.createNamespace(NAMESPACE);
        cluster.createCustomResources("../examples/templates/cluster-operator",
                "../examples/templates/topic-operator",
                TestUtils.CRD_KAFKA,
                TestUtils.CRD_KAFKA_CONNECT,
                TestUtils.CRD_KAFKA_CONNECT_S2I,
                TestUtils.CRD_TOPIC,
                "src/rbac/role-edit-kafka.yaml");
    }

    @Override
    protected void tearDownEnvironmentAfterAll() {
        cluster.deleteCustomResources();
        cluster.deleteNamespaces();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        LOGGER.info("Skipping env recreation after each test - resources should be same for whole test class!");
    }
}

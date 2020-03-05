/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnectS2IResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.connect.ConnectorPlugin;
import io.strimzi.api.kafka.model.status.KafkaConnectS2IStatus;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectS2IResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectorResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.FileUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectS2IUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@OpenShiftOnly
@Tag(REGRESSION)
class ConnectS2IST extends BaseST {

    public static final String NAMESPACE = "connect-s2i-cluster-test";
    private static final Logger LOGGER = LogManager.getLogger(ConnectS2IST.class);
    private static final String CONNECT_S2I_TOPIC_NAME = "connect-s2i-topic-example";

    private static final String KAFKA_CLIENTS_NAME = CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS;
    private String kafkaClientsPodName;

    @Test
    void testDeployS2IWithMongoDBPlugin() throws InterruptedException, IOException {
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-1";
        // Calls to Connect API are executed from kafka-0 pod
        String podForExecName = deployConnectS2IWithMongoDb(kafkaConnectS2IName, true);

        String mongoDbConfig = "{" +
                "\"name\": \"" + kafkaConnectS2IName + "\"," +
                "\"config\": {" +
                "   \"connector.class\" : \"io.debezium.connector.mongodb.MongoDbConnector\"," +
                "   \"tasks.max\" : \"1\"," +
                "   \"mongodb.hosts\" : \"debezium/localhost:27017\"," +
                "   \"mongodb.name\" : \"dbserver1\"," +
                "   \"mongodb.user\" : \"debezium\"," +
                "   \"mongodb.password\" : \"dbz\"," +
                "   \"database.history.kafka.bootstrap.servers\" : \"localhost:9092\"}" +
                "}";

        KafkaConnectS2IUtils.waitForConnectS2IStatus(kafkaConnectS2IName, "Ready");

        // Make sure that Connenct API is ready
        KafkaConnectS2IUtils.waitForRebalancingDone(kafkaConnectS2IName);

        checkConnectorInStatus(NAMESPACE, kafkaConnectS2IName);

        String createConnectorOutput = cmdKubeClient().execInPod(podForExecName, "curl", "-X", "POST", "-H", "Accept:application/json", "-H", "Content-Type:application/json",
                "http://" + KafkaConnectS2IResources.serviceName(kafkaConnectS2IName) + ":8083/connectors/", "-d", mongoDbConfig).out();
        LOGGER.info("Create Connector result: {}", createConnectorOutput);

        // Make sure that connector is really created
        Thread.sleep(10_000);

        String connectorStatus = cmdKubeClient().execInPod(podForExecName, "curl", "-X", "GET", "http://" + KafkaConnectS2IResources.serviceName(kafkaConnectS2IName) + ":8083/connectors/" + kafkaConnectS2IName + "/status").out();
        assertThat(connectorStatus, containsString("RUNNING"));
    }

    @Test
    void testDeployS2IAndKafkaConnectorWithMongoDBPlugin() throws IOException {
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-11";
        // Calls to Connect API are executed from kafka-0 pod
        String podForExecName = deployConnectS2IWithMongoDb(kafkaConnectS2IName, false);

        // Make sure that Connenct API is ready
        KafkaConnectS2IUtils.waitForRebalancingDone(kafkaConnectS2IName);

        KafkaConnectorResource.kafkaConnector(kafkaConnectS2IName)
            .withNewSpec()
                .withClassName("io.debezium.connector.mongodb.MongoDbConnector")
                .withTasksMax(2)
                .addToConfig("mongodb.hosts", "debezium/localhost:27017")
                .addToConfig("mongodb.name", "dbserver1")
                .addToConfig("mongodb.user", "debezium")
                .addToConfig("mongodb.password", "dbz")
                .addToConfig("database.history.kafka.bootstrap.servers", "localhost:9092")
            .endSpec().done();

        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);
        KafkaConnectUtils.waitForConnectorReady(kafkaConnectS2IName);

        checkConnectorInStatus(NAMESPACE, kafkaConnectS2IName);

        String connectorStatus = cmdKubeClient().execInPod(podForExecName, "curl", "-X", "GET", "http://" + KafkaConnectS2IResources.serviceName(kafkaConnectS2IName) + ":8083/connectors/" + kafkaConnectS2IName + "/status").out();
        assertThat(connectorStatus, containsString("RUNNING"));

        KafkaConnectorResource.replaceKafkaConnectorResource(kafkaConnectS2IName, kC -> {
            Map<String, Object> config = kC.getSpec().getConfig();
            config.put("mongodb.user", "test-user");
            kC.getSpec().setConfig(config);
            kC.getSpec().setTasksMax(8);
        });

        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);

        TestUtils.waitFor("mongodb.user and tasks.max upgrade in S2I connector", Constants.WAIT_FOR_ROLLING_UPDATE_INTERVAL, Constants.TIMEOUT_AVAILABILITY_TEST,
            () -> {
                String connectorConfig = cmdKubeClient().execInPod(podForExecName, "curl", "-X", "GET", "http://" + KafkaConnectS2IResources.serviceName(kafkaConnectS2IName) + ":8083/connectors/" + kafkaConnectS2IName + "/config").out();
                assertThat(connectorStatus, containsString("RUNNING"));
                return connectorConfig.contains("tasks.max\":\"8") && connectorConfig.contains("mongodb.user\":\"test-user");
            });
    }

    @Test
    void testSecretsWithKafkaConnectS2IWithTlsAndScramShaAuthentication() {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewKafkaListenerAuthenticationScramSha512Auth()
                            .endKafkaListenerAuthenticationScramSha512Auth()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        final String userName = "user-example-one";
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-2";

        KafkaUser user = KafkaUserResource.scramShaUser(CLUSTER_NAME, userName).done();

        SecretUtils.waitForSecretReady(userName);

        KafkaConnectS2IResource.kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1, true)
                .editMetadata()
                    .addToLabels("type", "kafka-connect-s2i")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .addNewTrustedCertificate()
                            .withSecretName(KafkaResources.clusterCaCertificateSecretName(CLUSTER_NAME))
                            .withCertificate("ca.crt")
                        .endTrustedCertificate()
                    .endTls()
                    .withBootstrapServers(KafkaResources.tlsBootstrapAddress(CLUSTER_NAME))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(userName)
                        .withNewPasswordSecret()
                            .withSecretName(userName)
                            .withPassword("password")
                        .endPasswordSecret()
                    .endKafkaClientAuthenticationScramSha512()
                .endSpec()
                .done();

        KafkaTopicResource.topic(CLUSTER_NAME, CONNECT_S2I_TOPIC_NAME).done();

        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, user).done();

        final String defaultKafkaClientsPodName =
                ResourceManager.kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        internalKafkaClient.setPodName(defaultKafkaClientsPodName);

        String kafkaConnectS2IPodName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        String kafkaConnectS2ILogs = kubeClient().logs(kafkaConnectS2IPodName);
        String execPod = KafkaResources.kafkaPodName(CLUSTER_NAME, 0);

        LOGGER.info("Verifying that in kafka connect logs are everything fine");
        assertThat(kafkaConnectS2ILogs, not(containsString("ERROR")));

        LOGGER.info("Creating FileStreamSink connector via pod {} with topic {}", execPod, CONNECT_S2I_TOPIC_NAME);
        KafkaConnectUtils.createFileSinkConnector(execPod, CONNECT_S2I_TOPIC_NAME, Constants.DEFAULT_SINK_FILE_NAME, KafkaConnectResources.url(kafkaConnectS2IName, NAMESPACE, 8083));

        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesTls(CONNECT_S2I_TOPIC_NAME, NAMESPACE, CLUSTER_NAME, userName, MESSAGE_COUNT, "TLS"),
                internalKafkaClient.receiveMessagesTls(CONNECT_S2I_TOPIC_NAME, NAMESPACE, CLUSTER_NAME, userName, MESSAGE_COUNT, "TLS", CONSUMER_GROUP_NAME + rng.nextInt(Integer.MAX_VALUE))
        );
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectS2IPodName, Constants.DEFAULT_SINK_FILE_NAME, "99");

        assertThat(cmdKubeClient().execInPod(kafkaConnectS2IPodName, "/bin/bash", "-c", "cat " + Constants.DEFAULT_SINK_FILE_NAME).out(),
                containsString("99"));
    }

    @Test
    void testCustomAndUpdatedValues() {
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-3";
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 1).done();

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");

        KafkaConnectS2IResource.kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1, true)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
            .endMetadata()
            .editSpec()
                .withNewTemplate()
                    .withNewConnectContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endConnectContainer()
                .endTemplate()
            .endSpec()
            .done();

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, String> connectSnapshot = DeploymentUtils.depConfigSnapshot(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName));

        LOGGER.info("Verify values before update");

        LabelSelector deploymentConfigSelector = new LabelSelectorBuilder().addToMatchLabels(kubeClient().getDeploymentConfigSelectors(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName))).build();
        String connectPodName = kubeClient().listPods(deploymentConfigSelector).get(0).getMetadata().getName();

        checkSpecificVariablesInContainer(connectPodName, KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), envVarGeneral);

        LOGGER.info("Updating values in ConnectS2I container");
        KafkaConnectS2IResource.replaceConnectS2IResource(kafkaConnectS2IName, kc -> {
            kc.getSpec().getTemplate().getConnectContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
        });

        DeploymentUtils.waitTillDepConfigHasRolled(kafkaConnectS2IName, connectSnapshot);

        deploymentConfigSelector = new LabelSelectorBuilder().addToMatchLabels(kubeClient().getDeploymentConfigSelectors(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName))).build();
        connectPodName = kubeClient().listPods(deploymentConfigSelector).get(0).getMetadata().getName();

        LOGGER.info("Verify values after update");
        checkSpecificVariablesInContainer(connectPodName, KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), envVarUpdated);
    }

    @Test
    void testJvmAndResources() {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3).done();

        final String kafkaConnectS2IName = "kafka-connect-s2i-name-4";

        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");

        KafkaConnectS2I kafkaConnectS2i = KafkaConnectS2IResource.kafkaConnectS2IWithoutWait(KafkaConnectS2IResource.defaultKafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1)
            .editMetadata()
                .addToLabels("type", "kafka-connect")
            .endMetadata()
            .editSpec()
                .withResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("400M"))
                        .addToLimits("cpu", new Quantity("2"))
                        .addToRequests("memory", new Quantity("300M"))
                        .addToRequests("cpu", new Quantity("1"))
                        .build())
                .withBuildResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("1000M"))
                        .addToLimits("cpu", new Quantity("1000"))
                        .addToRequests("memory", new Quantity("400M"))
                        .addToRequests("cpu", new Quantity("1000"))
                        .build())
                .withNewJvmOptions()
                    .withXmx("200m")
                    .withXms("200m")
                    .withServer(true)
                    .withXx(jvmOptionsXX)
                .endJvmOptions()
            .endSpec().build());

        KafkaConnectS2IUtils.waitForConnectS2IStatus(kafkaConnectS2IName, "NotReady");

        TestUtils.waitFor("build status: Pending", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_AVAILABILITY_TEST,
            () -> kubeClient().getClient().adapt(OpenShiftClient.class).builds().inNamespace(NAMESPACE).withName(kafkaConnectS2IName + "-connect-1").get().getStatus().getPhase().equals("Pending"));

        kubeClient().getClient().adapt(OpenShiftClient.class).builds().inNamespace(NAMESPACE).withName(kafkaConnectS2IName + "-connect-1").cascading(true).delete();

        KafkaConnectS2IResource.replaceConnectS2IResource(kafkaConnectS2IName, kc -> {
            kc.getSpec().setBuildResources(new ResourceRequirementsBuilder()
                    .addToLimits("memory", new Quantity("1000M"))
                    .addToLimits("cpu", new Quantity("1"))
                    .addToRequests("memory", new Quantity("400M"))
                    .addToRequests("cpu", new Quantity("1"))
                    .build());
        });

        TestUtils.waitFor("Kafka Connect CR change", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> kubeClient().getClient().adapt(OpenShiftClient.class).buildConfigs().inNamespace(NAMESPACE).withName(kafkaConnectS2IName + "-connect").get().getSpec().getResources().getRequests().get("cpu").equals(new Quantity("1")));

        cmdKubeClient().exec("start-build", KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), "-n", NAMESPACE);

        KafkaConnectS2IUtils.waitForConnectS2IStatus(kafkaConnectS2IName, "Ready");

        String podName = PodUtils.getPodNameByPrefix(kafkaConnectS2IName);

        assertResources(NAMESPACE, podName, kafkaConnectS2IName + "-connect",
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(podName, kafkaConnectS2IName + "-connect",
                "-Xmx200m", "-Xms200m", "-server", "-XX:+UseG1GC");

        KafkaConnectS2IResource.deleteKafkaConnectS2IWithoutWait(kafkaConnectS2i);
    }

    @Test
    void testKafkaConnectorWithConnectS2IAndConnectWithSameName() {
        String topicName = "test-topic-" + new Random().nextInt(Integer.MAX_VALUE);
        String connectClusterName = "connect-cluster";
        String connectS2IClusterName = "connect-s2i-cluster";

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3).done();
        // Create different connect cluster via S2I resources
        KafkaConnectS2IResource.kafkaConnectS2I(CLUSTER_NAME, 1, false)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
                .editSpec()
                .addToConfig("group.id", connectClusterName)
                .addToConfig("offset.storage.topic", connectClusterName + "-offsets")
                .addToConfig("config.storage.topic", connectClusterName + "-config")
                .addToConfig("status.storage.topic", connectClusterName + "-status")
            .endSpec().done();

        // Create connect cluster with default connect image
        KafkaConnectResource.kafkaConnectWithoutWait(KafkaConnectResource.defaultKafkaConnect(CLUSTER_NAME, CLUSTER_NAME, 1)
            .editMetadata()
                .addToLabels("type", "kafka-connect")
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("group.id", connectS2IClusterName)
                .addToConfig("offset.storage.topic", connectS2IClusterName + "-offsets")
                .addToConfig("config.storage.topic", connectS2IClusterName + "-config")
                .addToConfig("status.storage.topic", connectS2IClusterName + "-status")
            .endSpec().build());

        KafkaConnectUtils.waitForConnectStatus(CLUSTER_NAME, "NotReady");

        KafkaConnectorResource.kafkaConnector(CLUSTER_NAME)
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", topicName)
                .addToConfig("file", "/tmp/test-file-sink.txt")
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec().done();
        KafkaConnectUtils.waitForConnectorReady(CLUSTER_NAME);

        // Wait for Cluster Operator reconciliation
        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);

        // Check that KafkaConnectS2I contains created connector
        String connectS2IPodName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        String availableConnectors = KafkaConnectUtils.getCreatedConnectors(connectS2IPodName);
        assertThat(availableConnectors, containsString(CLUSTER_NAME));

        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);

        KafkaConnectUtils.waitForConnectStatus(CLUSTER_NAME, "NotReady");

        String newTopic = "new-topic";
        KafkaConnectorResource.replaceKafkaConnectorResource(CLUSTER_NAME, kc -> {
            kc.getSpec().getConfig().put("topics", newTopic);
            kc.getSpec().setTasksMax(8);
        });

        TestUtils.waitFor("mongodb.user and tasks.max upgrade in S2I connector", Constants.WAIT_FOR_ROLLING_UPDATE_INTERVAL, Constants.TIMEOUT_AVAILABILITY_TEST,
            () -> {
                String connectorConfig = cmdKubeClient().execInPod(connectS2IPodName, "curl", "-X", "GET", "http://localhost:8083/connectors/" + CLUSTER_NAME + "/config").out();
                return connectorConfig.contains("tasks.max\":\"8") && connectorConfig.contains("topics\":\"" + newTopic);
            });

        // Now delete KafkaConnector resource and create connector manually
        KafkaConnectorResource.kafkaConnectorClient().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();

        KafkaConnectS2IResource.replaceConnectS2IResource(CLUSTER_NAME, kc -> {
            kc.getMetadata().getAnnotations().remove(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES);
        });

        String execPodName = KafkaResources.kafkaPodName(CLUSTER_NAME, 0);
        KafkaConnectUtils.createFileSinkConnector(execPodName, topicName, Constants.DEFAULT_SINK_FILE_NAME, KafkaConnectResources.url(CLUSTER_NAME, NAMESPACE, 8083));
        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);

        // Check that KafkaConnect contains created connector
        availableConnectors = KafkaConnectUtils.getCreatedConnectors(connectS2IPodName);
        assertThat(availableConnectors, containsString("sink-test"));

        // Wait for Cluster Operator reconciliation
        StUtils.waitForReconciliation(testClass, testName, NAMESPACE);

        // Check that KafkaConnect contains created connector
        availableConnectors = KafkaConnectUtils.getCreatedConnectors(connectS2IPodName);
        assertThat(availableConnectors, containsString("sink-test"));

        KafkaConnectUtils.waitForConnectStatus(CLUSTER_NAME, "NotReady");
        KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(CLUSTER_NAME).delete();
    }

    private String deployConnectS2IWithMongoDb(String kafkaConnectS2IName, boolean allowNetworkPolicyAccess) throws IOException {
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 1).done();

        KafkaConnectS2IResource.kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1, allowNetworkPolicyAccess)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .done();

        Map<String, String> connectSnapshot = DeploymentUtils.depConfigSnapshot(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName));

        File dir = FileUtils.downloadAndUnzip("https://repo1.maven.org/maven2/io/debezium/debezium-connector-mongodb/0.7.5/debezium-connector-mongodb-0.7.5-plugin.zip");

        // Start a new image build using the plugins directory
        cmdKubeClient().execInCurrentNamespace("start-build", KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), "--from-dir", dir.getAbsolutePath());
        // Wait for rolling update connect pods
        DeploymentUtils.waitTillDepConfigHasRolled(kafkaConnectS2IName, connectSnapshot);
        String podForExecName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        LOGGER.info("Collect plugins information from connect s2i pod");

        String plugins = cmdKubeClient().execInPod(kafkaClientsPodName, "curl", "-X", "GET", "http://" + KafkaConnectS2IResources.serviceName(kafkaConnectS2IName) + ":8083/connector-plugins").out();

        assertThat(plugins, containsString("io.debezium.connector.mongodb.MongoDbConnector"));

        return podForExecName;
    }

    private void checkConnectorInStatus(String namespace, String kafkaConnectS2IName) {
        KafkaConnectS2IStatus kafkaConnectS2IStatus = KafkaConnectS2IResource.kafkaConnectS2IClient().inNamespace(namespace).withName(kafkaConnectS2IName).get().getStatus();
        List<ConnectorPlugin> pluginsList = kafkaConnectS2IStatus.getConnectorPlugins();
        assertThat(pluginsList, notNullValue());
        List<String> pluginsClasses = pluginsList.stream().map(p -> p.getConnectorClass()).collect(Collectors.toList());
        assertThat(pluginsClasses, hasItems("org.apache.kafka.connect.file.FileStreamSinkConnector",
                "org.apache.kafka.connect.file.FileStreamSourceConnector",
                "org.apache.kafka.connect.mirror.MirrorCheckpointConnector",
                "org.apache.kafka.connect.mirror.MirrorHeartbeatConnector",
                "org.apache.kafka.connect.mirror.MirrorSourceConnector",
                "io.debezium.connector.mongodb.MongoDbConnector"));
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE, Constants.CO_OPERATION_TIMEOUT_SHORT,  Constants.RECONCILIATION_INTERVAL).done();

        KafkaClientsResource.deployKafkaClients(false, KAFKA_CLIENTS_NAME).done();
        kafkaClientsPodName = kubeClient().listPodsByPrefixInName(KAFKA_CLIENTS_NAME).get(0).getMetadata().getName();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        super.recreateTestEnv(coNamespace, bindingsNamespaces, Constants.CO_OPERATION_TIMEOUT_SHORT);
    }
}

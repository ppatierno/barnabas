/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.annotations.ClusterOperator;
import io.strimzi.test.annotations.Namespace;
import io.strimzi.test.annotations.OpenShiftOnly;
import io.strimzi.test.extensions.StrimziExtension;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;

import static io.strimzi.test.extensions.StrimziExtension.CCI_FLAKY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@ExtendWith(StrimziExtension.class)
@Namespace(ConnectS2IST.NAMESPACE)
@ClusterOperator
class ConnectS2IST extends AbstractST {

    public static final String NAMESPACE = "connect-s2i-cluster-test";
    public static final String CONNECT_CLUSTER_NAME = "connect-s2i-tests";
    public static final String CONNECT_DEPLOYMENT_NAME = CONNECT_CLUSTER_NAME + "-connect";
    private static final Logger LOGGER = LogManager.getLogger(ConnectS2IST.class);
    private static Resources classResources;

    @Test
    @OpenShiftOnly
    @Tag(CCI_FLAKY)
    void testDeployS2IWithMongoDBPlugin() throws IOException {
        resources().kafkaConnectS2I(CONNECT_CLUSTER_NAME, 1)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
            .endMetadata()
            .done();

        File dir = StUtils.downloadAndUnzip("https://repo1.maven.org/maven2/io/debezium/debezium-connector-mongodb/0.3.0/debezium-connector-mongodb-0.3.0-plugin.zip");

        String connectS2IPodName = kubeClient.listResourcesByLabel("pod", "type=kafka-connect-s2i").get(0);

        // Start a new image build using the plugins directory
        kubeClient.exec("oc", "start-build", CONNECT_DEPLOYMENT_NAME, "--from-dir", dir.getAbsolutePath());
        kubeClient.waitForResourceDeletion("pod", connectS2IPodName);

        kubeClient.waitForDeploymentConfig(CONNECT_DEPLOYMENT_NAME);

        connectS2IPodName = kubeClient.listResourcesByLabel("pod", "type=kafka-connect-s2i").get(0);
        String plugins = kubeClient.execInPod(connectS2IPodName, "curl", "-X", "GET", "http://localhost:8083/connector-plugins").out();

        assertThat(plugins, containsString("io.debezium.connector.mongodb.MongoDbConnector"));
    }

    @BeforeEach
    void createTestResources() {
        createResources();
    }

    @AfterEach
    void deleteTestResources() throws Exception {
        deleteResources();
    }

    @BeforeAll
    static void createClassResources() {
        LOGGER.info("Creating resources before the test class");
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        testClassResources.clusterOperator(NAMESPACE).done();

        classResources = new Resources(namespacedClient());
        classResources().kafkaEphemeral(CONNECT_CLUSTER_NAME, 3).done();
    }

    private static Resources classResources() {
        return classResources;
    }
}

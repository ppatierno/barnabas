/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.test.ClusterController;
import io.strimzi.test.ConnectCluster;
import io.strimzi.test.KafkaCluster;
import io.strimzi.test.Namespace;
import io.strimzi.test.OpenShiftOnly;
import io.strimzi.test.Resources;
import io.strimzi.test.StrimziRunner;
import io.strimzi.test.k8s.Oc;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.strimzi.test.TestUtils.map;

@RunWith(StrimziRunner.class)
@Namespace(ConnectClusterIT.NAMESPACE)
@ClusterController
@KafkaCluster(name = ConnectClusterIT.KAFKA_CLUSTER_NAME)
public class ConnectClusterIT extends AbstractClusterIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectClusterIT.class);

    public static final String NAMESPACE = "connect-cluster-test";
    public static final String KAFKA_CLUSTER_NAME = "connect-tests";
    public static final String BOOTSTRAP_SERVERS = KAFKA_CLUSTER_NAME + "-kafka:9092";

    @Test
    @Resources(value = "../examples/templates/cluster-controller", asAdmin = true)
    @OpenShiftOnly
    public void testDeployConnectClusterViaTemplate() {
        Oc oc = (Oc) this.kubeClient;
        String clusterName = "openshift-my-connect-cluster";
        oc.newApp("strimzi-connect", map("CLUSTER_NAME", clusterName,
                "KAFKA_CONNECT_BOOTSTRAP_SERVERS", BOOTSTRAP_SERVERS));
        String deploymentName = clusterName + "-connect";
        oc.waitForDeployment(deploymentName);
        oc.deleteByName("cm", clusterName);
        oc.waitForResourceDeletion("deployment", deploymentName);
    }

    @Test
    @ConnectCluster(name = "my-cluster", bootstrapServers = BOOTSTRAP_SERVERS)
    public void testDeployUndeploy() {
        LOGGER.info("Looks like the connect cluster my-cluster deployed OK");
    }

}

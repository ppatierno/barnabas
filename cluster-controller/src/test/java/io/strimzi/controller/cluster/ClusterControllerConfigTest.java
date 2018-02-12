package io.strimzi.controller.cluster;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;


import static org.junit.Assert.assertEquals;

public class ClusterControllerConfigTest {

    private static Map<String, String> labels = new HashMap<>(1);

    static {
        labels.put("strimzi.io/kind", "cluster");
    }

    @Test
    public void testDefaultConfig() {
        ClusterControllerConfig config = new ClusterControllerConfig("namespace", labels);

        assertEquals("namespace", config.getNamespace());
        assertEquals(labels, config.getLabels());
        assertEquals(120_000, config.getReconciliationInterval());
    }

    @Test
    public void testReconciliationInterval() {

        ClusterControllerConfig config = new ClusterControllerConfig("namespace", labels, 60000);

        assertEquals("namespace", config.getNamespace());
        assertEquals(labels, config.getLabels());
        assertEquals(60_000, config.getReconciliationInterval());
    }

    @Test
    public void testEnvVars() {

        Map<String, String> envVars = new HashMap<>(2);
        envVars.put(ClusterControllerConfig.STRIMZI_NAMESPACE, "namespace");
        envVars.put(ClusterControllerConfig.STRIMZI_CONFIGMAP_LABELS, "strimzi.io/kind=cluster");
        envVars.put(ClusterControllerConfig.STRIMZI_FULL_RECONCILIATION_INTERVAL, "30000");

        ClusterControllerConfig config = ClusterControllerConfig.fromMap(envVars);

        assertEquals("namespace", config.getNamespace());
        assertEquals(labels, config.getLabels());
        assertEquals(30_000, config.getReconciliationInterval());
    }
}

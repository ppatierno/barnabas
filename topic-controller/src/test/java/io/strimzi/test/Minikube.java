/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test;

import static io.strimzi.test.Exec.exec;
import static io.strimzi.test.Exec.execOutput;
import static io.strimzi.test.Exec.isExecutableOnPath;

/**
 * A {@link KubeCluster} implementation for {@code minikube} and {@code minishift}.
 */
public class Minikube implements KubeCluster {

    public static final String MINIKUBE = "minikube";
    public static final String MINISHIFT = "minishift";

    private final String cmd;

    private Minikube(String cmd) {
        this.cmd = cmd;
    }

    public static Minikube minikube() {
        return new Minikube(MINIKUBE);
    }

    public static Minikube minishift() {
        return new Minikube(MINISHIFT);
    }

    @Override
    public boolean isAvailable() {
        return isExecutableOnPath(cmd);
    }

    @Override
    public boolean isClusterUp() {
        String output = execOutput(cmd, "status");
        return output.contains("minikube: Running")
                && output.contains("cluster: Running")
                && output.contains("kubectl: Correctly Configured:");
    }

    @Override
    public void clusterUp() {
        exec(cmd, "start");
    }

    @Override
    public void clusterDown() {
        exec(cmd, "stop");
    }

    @Override
    public KubeClient defaultClient() {
        return MINIKUBE.equals(cmd) ? new Kubectl() : new Oc();
    }
}

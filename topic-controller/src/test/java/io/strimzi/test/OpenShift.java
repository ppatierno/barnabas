/*
 * Copyright 2018 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.strimzi.test;

import static io.strimzi.test.Exec.*;

public class OpenShift implements KubeCluster {

    private static final String OC = "oc";

    @Override
    public boolean isAvailable() {
        return isExecutableOnPath(OC);
    }

    @Override
    public boolean isClusterUp() {
        try {
            exec(OC, "cluster", "status");
            return true;
        } catch (KubeClusterException e) {
            if (e.statusCode == 1) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void clusterUp() {
        exec(OC, "cluster", "up");
    }

    @Override
    public void clusterDown() {
        exec(OC, "cluster", "down");
    }

    @Override
    public KubeClient defaultClient() {
        return new Oc();
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Oc extends KubeCluster {

    public static final String OC = "oc";

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
    public void createRole(String roleName, Permission... permissions) {
        exec(OC, "login", "-u", "system:admin");
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList(OC, "create", "role", roleName));
        for (Permission p: permissions) {
            for (String resource: p.resource()) {
                cmd.add("--resource=" + resource);
            }
            for (int i = 0; i < p.verbs().length; i++) {
                cmd.add("--verb="+p.verbs()[i]);
            }
        }
        exec(cmd);
        exec(OC, "login", "-u", "developer");
    }

    @Override
    public void createRoleBinding(String bindingName, String roleName, String... user) {
        exec(OC, "login", "-u", "system:admin");
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList(OC, "create", "rolebinding", bindingName, "--role="+roleName));
        for (int i = 0; i < user.length; i++) {
            cmd.add("--user="+user[i]);
        }
        exec(cmd);
        exec(OC, "login", "-u", "developer");
    }

    @Override
    public void deleteRoleBinding(String bindingName) {
        exec(OC, "login", "-u", "system:admin");
        exec(OC, "delete", "rolebinding", bindingName);
        exec(OC, "login", "-u", "developer");
    }

    @Override
    public void deleteRole(String roleName) {
        exec(OC, "login", "-u", "system:admin");
        exec(OC, "delete", "role", roleName);
        exec(OC, "login", "-u", "developer");
    }

    @Override
    public String defaultNamespace() {
        return "myproject";
    }

}

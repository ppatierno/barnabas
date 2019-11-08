/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.init;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InitWriterConfigTest {

    private static Map<String, String> envVars = new HashMap<>(3);

    static {
        envVars.put(InitWriterConfig.NODE_NAME, "localhost");
        envVars.put(InitWriterConfig.RACK_TOPOLOGY_KEY, "failure-domain.beta.kubernetes.io/zone");
        envVars.put(InitWriterConfig.EXTERNAL_ADDRESS, "TRUE");
    }

    @Test
    public void testEnvVars() {
        InitWriterConfig config = InitWriterConfig.fromMap(envVars);

        assertThat(config.getNodeName(), is("localhost"));
        assertThat(config.getRackTopologyKey(), is("failure-domain.beta.kubernetes.io/zone"));
        assertThat(config.isExternalAddress(), is(true));
    }

    @Test
    public void testEmptyEnvVars() {
        assertThrows(IllegalArgumentException.class, () -> {
            InitWriterConfig.fromMap(Collections.emptyMap());
        });
    }

    @Test
    public void testNoNodeName() {
        assertThrows(IllegalArgumentException.class, () -> {
            Map<String, String> envVars = new HashMap<>(InitWriterConfigTest.envVars);
            envVars.remove(InitWriterConfig.NODE_NAME);

            InitWriterConfig.fromMap(envVars);
        });
    }
}

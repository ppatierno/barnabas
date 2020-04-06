/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import io.strimzi.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The purpose of this test is to confirm that we can create a
 * resource from the POJOs, serialize it and create the resource in K8S.
 * I.e. that such instance resources obtained from POJOs are valid according to the schema
 * validation done by K8S.
 */
public class KafkaUserCrdIT extends AbstractCrdIT {
    public static final String NAMESPACE = "kafkausercrd-it";

    @Test
    void testKafkaUserV1alpha1() {
        assumeKube1_11Plus();
        createDelete(KafkaUser.class, "KafkaUserV1alpha1.yaml");
    }

    @Test
    void testKafkaUserV1beta1() {
        createDelete(KafkaUser.class, "KafkaUserV1beta1.yaml");
    }

    @Test
    void testKafkaUserMinimal() {
        createDelete(KafkaUser.class, "KafkaUser-minimal.yaml");
    }

    @Test
    void testKafkaUserWithExtraProperty() {
        createDelete(KafkaUser.class, "KafkaUser-with-extra-property.yaml");
    }

    @BeforeAll
    void setupEnvironment() {
        cluster.createNamespace(NAMESPACE);
        cluster.createCustomResources(TestUtils.CRD_KAFKA_USER);
        cluster.cmdClient().waitForResourceCreation("crd", "kafkausers.kafka.strimzi.io");
    }

    @AfterAll
    void teardownEnvironment() {
        cluster.deleteCustomResources();
        cluster.deleteNamespaces();
    }
}

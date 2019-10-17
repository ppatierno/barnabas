/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.strimzi.systemtest.Constants.REGRESSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

@Tag(REGRESSION)
class MultipleNamespaceST extends AbstractNamespaceST {

    private static final Logger LOGGER = LogManager.getLogger(MultipleNamespaceST.class);

    /**
     * Test the case where the TO is configured to watch a different namespace that it is deployed in
     */
    @Test
    void testTopicOperatorWatchingOtherNamespace() {
        LOGGER.info("Deploying TO to watch a different namespace that it is deployed in");
        setNamespace(SECOND_NAMESPACE);
        List<String> topics = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
        assertThat(topics, not(hasItems(TOPIC_NAME)));

        deployNewTopic(CO_NAMESPACE, SECOND_NAMESPACE, TOPIC_NAME);
        deleteNewTopic(CO_NAMESPACE, TOPIC_NAME);
        setNamespace(CO_NAMESPACE);
    }

    /**
     * Test the case when Kafka will be deployed in different namespace than CO
     */
    @Test
    void testKafkaInDifferentNsThanClusterOperator() {
        LOGGER.info("Deploying Kafka in different namespace than CO when CO watches multiple namespaces");
        checkKafkaInDiffNamespaceThanCO(CLUSTER_NAME, SECOND_NAMESPACE);
    }

    /**
     * Test the case when MirrorMaker will be deployed in different namespace across multiple namespaces
     */
    @Test
    void testDeployMirrorMakerAcrossMultipleNamespace() {
        LOGGER.info("Deploying Kafka MirrorMaker in different namespace than CO when CO watches multiple namespaces");
        checkMirrorMakerForKafkaInDifNamespaceThanCO(CLUSTER_NAME);
    }

    @BeforeAll
    void setupEnvironment() {
        deployTestSpecificResources();
    }

    private void deployTestSpecificResources() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(CO_NAMESPACE, Arrays.asList(CO_NAMESPACE, SECOND_NAMESPACE));
        createTestClassResources();

        applyRoleBindings(CO_NAMESPACE);
        applyRoleBindings(CO_NAMESPACE, SECOND_NAMESPACE);

        LOGGER.info("Deploying CO to watch multiple namespaces");
        testClassResources().clusterOperator(String.join(",", CO_NAMESPACE, SECOND_NAMESPACE)).done();

        setNamespace(SECOND_NAMESPACE);
        secondNamespaceResources = new Resources(kubeClient(SECOND_NAMESPACE));

        secondNamespaceResources.kafkaEphemeral(CLUSTER_NAME, 3)
            .editSpec()
                .editEntityOperator()
                    .editTopicOperator()
                        .withWatchedNamespace(CO_NAMESPACE)
                    .endTopicOperator()
                .endEntityOperator()
            .endSpec().done();

        setNamespace(CO_NAMESPACE);
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        deployTestSpecificResources();
    }
}

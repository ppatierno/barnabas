/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.model;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.test.TestUtils;
import io.strimzi.test.logging.TestLogger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class ValidationVisitorTest {

    @Test
    public void testValidationErrorsAreLogged() {
        Kafka k = TestUtils.fromYaml("/example.yaml", Kafka.class, true);
        assertThat(k, is(notNullValue()));
        TestLogger logger = new TestLogger((Logger) LogManager.getLogger(ValidationVisitorTest.class));
        HasMetadata resource = new KafkaBuilder()
                .withNewMetadata()
                    .withName("testname")
                    .withNamespace("testnamespace")
                .endMetadata()
                .withApiVersion("v1alpha1")
            .build();

        Set<Condition> warningConditions = new HashSet<>();

        ResourceVisitor.visit(k, new ValidationVisitor(resource, logger, warningConditions));

        List<String> warningMessages = warningConditions.stream().map(Condition::getMessage).collect(Collectors.toList());

        assertThat(warningMessages, hasItem("Contains object at path spec.kafka with an unknown property: foo"));
        assertThat(warningMessages, hasItem("In API version v1alpha1 the property topicOperator at path spec.topicOperator has been deprecated. " +
                "This feature should now be configured at path spec.entityOperator.topicOperator."));
        assertThat(warningMessages, hasItem("In API version v1alpha1 the object kafkaListeners at path spec.kafka.listeners.kafkaListeners has been deprecated. " +
                "This object has been replaced with GenericKafkaListener."));
        assertThat(warningMessages, hasItem("In API version v1alpha1 the object topicOperator at path spec.topicOperator has been deprecated. " +
                "This object has been replaced with EntityTopicOperatorSpec."));

        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
            && ("Kafka resource testname in namespace testnamespace: " +
                "Contains object at path spec.kafka with an unknown property: foo").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property topicOperator at path spec.topicOperator has been deprecated. " +
                "This feature should now be configured at path spec.entityOperator.topicOperator.").equals(lm.formattedMessage()));
        logger.assertNotLogged(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property tolerations at path spec.zookeeper.tolerations has been deprecated. " +
                "This feature should now be configured at path spec.zookeeper.template.pod.tolerations.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the object kafkaListeners at path spec.kafka.listeners.kafkaListeners has been deprecated. " +
                "This object has been replaced with GenericKafkaListener.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the object topicOperator at path spec.topicOperator has been deprecated. " +
                "This object has been replaced with EntityTopicOperatorSpec.").equals(lm.formattedMessage()));
    }

    @Test
    public void testV1Beta1Deprecations() {
        Kafka k = TestUtils.fromYaml("/v1beta1Deprecations.yaml", Kafka.class, true);
        assertThat(k, is(notNullValue()));
        TestLogger logger = new TestLogger((Logger) LogManager.getLogger(ValidationVisitorTest.class));
        HasMetadata resource = new KafkaBuilder()
                .withNewMetadata()
                    .withName("testname")
                    .withNamespace("testnamespace")
                .endMetadata()
                .withApiVersion("v1alpha1")
                .build();

        Set<Condition> warningConditions = new HashSet<>();

        ResourceVisitor.visit(k, new ValidationVisitor(resource, logger, warningConditions));

        List<String> warningMessages = warningConditions.stream().map(Condition::getMessage).collect(Collectors.toList());

        assertThat(warningMessages, hasItem("In API version v1alpha1 the property topicOperator at path spec.topicOperator has been deprecated. " +
                "This feature should now be configured at path spec.entityOperator.topicOperator."));
        assertThat(warningMessages, hasItem("In API version v1alpha1 the object topicOperator at path spec.topicOperator has been deprecated. " +
                "This object has been replaced with EntityTopicOperatorSpec."));

        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property affinity at path spec.zookeeper.affinity has been deprecated. " +
                "This feature should now be configured at path spec.zookeeper.template.pod.affinity.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property tolerations at path spec.zookeeper.tolerations has been deprecated. " +
                "This feature should now be configured at path spec.zookeeper.template.pod.tolerations.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property affinity at path spec.kafka.affinity has been deprecated. " +
                "This feature should now be configured at path spec.kafka.template.pod.affinity.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property tolerations at path spec.kafka.tolerations has been deprecated. " +
                "This feature should now be configured at path spec.kafka.template.pod.tolerations.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the property topicOperator at path spec.topicOperator has been deprecated. " +
                "This feature should now be configured at path spec.entityOperator.topicOperator.").equals(lm.formattedMessage()));
        logger.assertLoggedAtLeastOnce(lm -> lm.level() == Level.WARN
                && ("Kafka resource testname in namespace testnamespace: " +
                "In API version v1alpha1 the object topicOperator at path spec.topicOperator has been deprecated. " +
                "This object has been replaced with EntityTopicOperatorSpec.").equals(lm.formattedMessage()));
    }


}

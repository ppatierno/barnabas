/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.kafka.listeners;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.KafkaListeners;
import io.strimzi.api.kafka.model.listener.KafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListeners;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.kafkaclients.externalClients.BasicExternalKafkaClient;
import io.strimzi.systemtest.kafkaclients.internalClients.InternalKafkaClient;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.ResourceOperation;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUserUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.ServiceUtils;
import io.strimzi.test.TestUtils;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.LOADBALANCER_SUPPORTED;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.TLS_LISTENER_DEFAULT_NAME;
import static io.strimzi.systemtest.enums.CustomResourceStatus.Ready;
import static io.strimzi.systemtest.resources.ResourceManager.CR_CREATION_TIMEOUT;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(REGRESSION)
public class BackwardsCompatibleListenersST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(BackwardsCompatibleListenersST.class);
    public static final String NAMESPACE = "bc-listeners";

    // Backwards compatibility needs to use v1beta1. That is why we have some custom methods here instead of using KafkaResource class
    private static MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaV1Beta1Client() {
        return Crds.kafkaV1Beta1Operation(ResourceManager.kubeClient().getClient());
    }

    private static Kafka createAndWaitForReadiness(Kafka kafka) {
        TestUtils.waitFor("Kafka creation", Constants.POLL_INTERVAL_FOR_RESOURCE_CREATION, CR_CREATION_TIMEOUT,
            () -> {
                try {
                    kafkaV1Beta1Client().inNamespace(ResourceManager.kubeClient().getNamespace()).createOrReplace(kafka);
                    return true;
                } catch (KubernetesClientException e) {
                    if (e.getMessage().contains("object is being deleted")) {
                        return false;
                    } else {
                        throw e;
                    }
                }
            });
        return waitFor(deleteLater(kafka));
    }

    private static Kafka waitFor(Kafka kafka) {
        long timeout = ResourceOperation.getTimeoutForResourceReadiness(kafka.getKind());
        return ResourceManager.waitForResourceStatus(kafkaV1Beta1Client(), kafka, Ready, timeout);
    }

    private static Kafka deleteLater(Kafka kafka) {
        return ResourceManager.deleteLater(KafkaResource.kafkaClient(), kafka);
    }

    /**
     * Test sending messages over tls transport using mutual tls auth
     */
    @Test
    @Tag(INTERNAL_CLIENTS_USED)
    void testSendMessagesTlsAuthenticated() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewTls()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endTls()
                .build();

        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName).build());
        KafkaUser user = KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.tlsUser(clusterName, kafkaUsername).build());

        KafkaClientsResource.createAndWaitForReadiness(KafkaClientsResource.deployKafkaClients(true, clusterName + "-" + Constants.KAFKA_CLIENTS, user).build());

        final String kafkaClientsPodName =
            ResourceManager.kubeClient().listPodsByPrefixInName(clusterName + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(kafkaUsername)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(TLS_LISTENER_DEFAULT_NAME)
            .build();

        // Check brokers availability
        LOGGER.info("Checking produced and consumed messages to pod: {}", kafkaClientsPodName);

        internalKafkaClient.checkProducedAndConsumedMessages(
            internalKafkaClient.sendMessagesTls(),
            internalKafkaClient.receiveMessagesTls()
        );
    }

    /**
     * Test sending messages over plain transport using scram sha auth
     */
    @Test
    @Tag(INTERNAL_CLIENTS_USED)
    void testSendMessagesPlainScramSha() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewPlain()
                    .withAuth(new KafkaListenerAuthenticationScramSha512())
                .endPlain()
                .build();

        // Use a Kafka with plain listener disabled
        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName).build());
        KafkaUser kafkaUser = KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.scramShaUser(clusterName, kafkaUsername).build());

        KafkaClientsResource.createAndWaitForReadiness(KafkaClientsResource.deployKafkaClients(false, clusterName + "-" + Constants.KAFKA_CLIENTS, kafkaUser).build());

        final String kafkaClientsPodName =
            ResourceManager.kubeClient().listPodsByPrefixInName(clusterName + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(kafkaUsername)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
            .build();

        // Check brokers availability
        LOGGER.info("Checking produced and consumed messages to pod: {}", kafkaClientsPodName);

        internalKafkaClient.checkProducedAndConsumedMessages(
            internalKafkaClient.sendMessagesPlain(),
            internalKafkaClient.receiveMessagesPlain()
        );
    }

    @Test
    @Tag(ACCEPTANCE)
    @Tag(NODEPORT_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    void testNodePortTls() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewKafkaListenerExternalNodePort()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endKafkaListenerExternalNodePort()
                .build();

        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3, 1)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName).build());
        KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.tlsUser(clusterName, kafkaUsername).build());

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(kafkaUsername)
            .withSecurityProtocol(SecurityProtocol.SSL)
            .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        basicExternalKafkaClient.verifyProducedAndConsumedMessages(
            basicExternalKafkaClient.sendMessagesTls(),
            basicExternalKafkaClient.receiveMessagesTls()
        );
    }

    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    void testLoadBalancerTls() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewKafkaListenerExternalLoadBalancer()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endKafkaListenerExternalLoadBalancer()
                .build();

        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName).build());
        KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.tlsUser(clusterName, kafkaUsername).build());

        ServiceUtils.waitUntilAddressIsReachable(kafkaV1Beta1Client().inNamespace(NAMESPACE).withName(clusterName).get().getStatus().getListeners().get(0).getAddresses().get(0).getHost());

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
                .withTopicName(topicName)
                .withNamespaceName(NAMESPACE)
                .withClusterName(clusterName)
                .withMessageCount(MESSAGE_COUNT)
                .withKafkaUsername(kafkaUsername)
                .withSecurityProtocol(SecurityProtocol.SSL)
                .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                .build();

        basicExternalKafkaClient.verifyProducedAndConsumedMessages(
            basicExternalKafkaClient.sendMessagesTls(),
            basicExternalKafkaClient.receiveMessagesTls()
        );
    }

    @Test
    @OpenShiftOnly
    @Tag(EXTERNAL_CLIENTS_USED)
    void testRouteTls() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewKafkaListenerExternalRoute()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endKafkaListenerExternalRoute()
                .build();

        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName).build());
        KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.tlsUser(clusterName, kafkaUsername).build());

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
                .withTopicName(topicName)
                .withNamespaceName(NAMESPACE)
                .withClusterName(clusterName)
                .withMessageCount(MESSAGE_COUNT)
                .withKafkaUsername(kafkaUsername)
                .withSecurityProtocol(SecurityProtocol.SSL)
                .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                .build();

        basicExternalKafkaClient.verifyProducedAndConsumedMessages(
                basicExternalKafkaClient.sendMessagesTls(),
                basicExternalKafkaClient.receiveMessagesTls()
        );
    }

    /**
     * When the listeners are converted from the old format to the new format, nothing should change. So no rolling
     * update should happen.
     */
    @Test
    @Tag(ACCEPTANCE)
    @Tag(NODEPORT_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    void testCustomResourceConversion() {
        String kafkaUsername = KafkaUserUtils.generateRandomNameOfKafkaUser();
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();

        KafkaListeners listeners = new KafkaListenersBuilder()
                .withNewPlain()
                    .withAuth(new KafkaListenerAuthenticationScramSha512())
                .endPlain()
                .withNewTls()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endTls()
                .withNewKafkaListenerExternalNodePort()
                    .withAuth(new KafkaListenerAuthenticationTls())
                .endKafkaListenerExternalNodePort()
                .build();

        createAndWaitForReadiness(KafkaResource.kafkaEphemeral(clusterName, 3, 1)
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .editSpec()
                    .editKafka()
                        .withListeners(new ArrayOrObjectKafkaListeners(listeners))
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.createAndWaitForReadiness(KafkaTopicResource.topic(clusterName, topicName, 1, 3, 2).build());
        KafkaUser user = KafkaUserResource.createAndWaitForReadiness(KafkaUserResource.tlsUser(clusterName, kafkaUsername).build());

        KafkaClientsResource.createAndWaitForReadiness(KafkaClientsResource.deployKafkaClients(true, clusterName + "-" + Constants.KAFKA_CLIENTS, user).build());
        final String kafkaClientsPodName = ResourceManager.kubeClient().listPodsByPrefixInName(clusterName + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(kafkaUsername)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        LOGGER.info("Checking produced and consumed messages to pod: {}", kafkaClientsPodName);
        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesTls(),
                internalKafkaClient.receiveMessagesTls()
        );

        LOGGER.info("Collect the pod information before update");
        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(clusterName));

        LOGGER.info("Update the custom resource to new format");
        KafkaResource.replaceKafkaResource(clusterName, kafka -> {
            kafka.getSpec().getKafka()
                    .setListeners(new ArrayOrObjectKafkaListeners(asList(
                            new GenericKafkaListenerBuilder()
                                    .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                                    .withPort(9092)
                                    .withType(KafkaListenerType.INTERNAL)
                                    .withTls(false)
                                    .withAuth(new KafkaListenerAuthenticationScramSha512())
                                    .build(),
                            new GenericKafkaListenerBuilder()
                                    .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                                    .withPort(9093)
                                    .withType(KafkaListenerType.INTERNAL)
                                    .withTls(true)
                                    .withAuth(new KafkaListenerAuthenticationTls())
                                    .build(),
                            new GenericKafkaListenerBuilder()
                                    .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                                    .withPort(9094)
                                    .withType(KafkaListenerType.NODEPORT)
                                    .withTls(true)
                                    .withAuth(new KafkaListenerAuthenticationTls())
                                    .build()
                    )));
        });

        KafkaUtils.waitForKafkaStatusUpdate(clusterName);

        LOGGER.info("Checking produced and consumed messages to pod: {}", kafkaClientsPodName);
        internalKafkaClient.checkProducedAndConsumedMessages(
            internalKafkaClient.sendMessagesTls(),
            internalKafkaClient.receiveMessagesTls()
        );

        LOGGER.info("Check if Kafka pods didn't roll");
        assertThat(StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(clusterName)), is(kafkaPods));
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        installClusterOperator(NAMESPACE);
    }

    @Override
    protected void tearDownEnvironmentAfterEach() throws Exception {
        super.tearDownEnvironmentAfterEach();
        kubeClient().getClient().persistentVolumeClaims().inNamespace(NAMESPACE).delete();
    }
}

/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.kafka;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalLoadBalancerBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalNodePortBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalRouteBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerTlsBuilder;
import io.strimzi.systemtest.BaseST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.strimzi.systemtest.Constants.LOADBALANCER_SUPPORTED;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(REGRESSION)
public class ListenersST extends BaseST {
    private static final Logger LOGGER = LogManager.getLogger(ListenersST.class);

    public static final String NAMESPACE = "kafka-listeners-cluster-test";

    private String customCertChain1 = "custom-certificate-chain-1";
    private String customCertChain2 = "custom-certificate-chain-2";
    private String customCertServer1 = "custom-certificate-server-1";
    private String customCertServer2 = "custom-certificate-server-2";
    private String customRootCA1 = "custom-certificate-root-1";
    private String customRootCA2 = "custom-certificate-root-2";

    private String userName = "alice";

    @Test
    @Tag(NODEPORT_SUPPORTED)
    void testCustomSoloCertificatesForNodePort() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalNodePort()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-1");
        assertThat(received, is(20));
    }

    @Test
    @Tag(NODEPORT_SUPPORTED)
    void testCustomChainCertificatesForNodePort() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalNodePort()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customRootCA1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-2");
        assertThat(received, is(20));
    }

    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    void testCustomSoloCertificatesForLoadBalancer() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalLoadBalancer()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalLoadBalancer()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-3");
        assertThat(received, is(20));
    }

    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    void testCustomChainCertificatesForLoadBalancer() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalLoadBalancer()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalLoadBalancer()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customRootCA1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-4");
        assertThat(received, is(20));
    }

    @Test
    @OpenShiftOnly
    void testCustomSoloCertificatesForRoute() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                         .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalRoute()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalRoute()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-5");
        assertThat(received, is(20));
    }

    @Test
    @OpenShiftOnly
    void testCustomChainCertificatesForRoute() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                        .withNewKafkaListenerExternalRoute()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertChain1)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endKafkaListenerExternalRoute()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        externalBasicKafkaClient.setCaCertName(customRootCA1);
        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "TLS", "consumer-group-certs-6");
        assertThat(received, is(20));
    }


    @Test
    @Tag(NODEPORT_SUPPORTED)
    void testCustomCertNodePortAndTlsRollingUpdate() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaPersistent(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        Map<String, String> kafkaSnapshot = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalNodePortBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer1)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
            kafka.getSpec().getKafka().getListeners().setTls(new KafkaListenerTlsBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer2)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
        });

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(20));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 30, "TLS", "consumer-group-certs-71");
        assertThat(received, is(30));

        SecretUtils.createCustomSecret(customCertServer1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.key").getFile());

        SecretUtils.createCustomSecret(customCertServer2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.key").getFile());

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 40, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(40));

        sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 50, "TLS", "consumer-group-certs-72");
        assertThat(received, is(50));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalNodePortBuilder()
                .withTls(true)
                .build());
        });

        StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(null);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(60));

        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "TLS", "consumer-group-certs-73");
        assertThat(received, is(60));
    }

    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    void testCustomCertLoadBalancerAndTlsRollingUpdate() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaPersistent(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalLoadBalancer()
                        .endKafkaListenerExternalLoadBalancer()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        Map<String, String> kafkaSnapshot = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalLoadBalancerBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer1)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
            kafka.getSpec().getKafka().getListeners().setTls(new KafkaListenerTlsBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer2)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
        });

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(20));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 30, "TLS", "consumer-group-certs-81");
        assertThat(received, is(30));

        SecretUtils.createCustomSecret(customCertServer1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.key").getFile());

        SecretUtils.createCustomSecret(customCertServer2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.key").getFile());

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 40, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(40));

        sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 50, "TLS", "consumer-group-certs-82");
        assertThat(received, is(50));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalNodePortBuilder()
                .withTls(true)
                .build());
        });

        StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(null);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(60));

        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "TLS", "consumer-group-certs-83");
        assertThat(received, is(60));
    }

    @Test
    @OpenShiftOnly
    void testCustomCertRouteAndTlsRollingUpdate() throws Exception {
        String topicName = "test-topic-" + rng.nextInt(Integer.MAX_VALUE);

        KafkaResource.kafkaPersistent(CLUSTER_NAME, 3, 3)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec().done();

        KafkaUser aliceUser = KafkaUserResource.tlsUser(CLUSTER_NAME, userName).done();

        Future producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        Future consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(10));

        Map<String, String> kafkaSnapshot = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalRouteBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer1)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
            kafka.getSpec().getKafka().getListeners().setTls(new KafkaListenerTlsBuilder()
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withSecretName(customCertServer2)
                        .withKey("ca.key")
                        .withCertificate("ca.crt")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build());
        });

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(customCertServer1);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 20, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(20));

        // Deploy client pod with custom certificates and collect messages from internal TLS listener
        KafkaClientsResource.deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, false, aliceUser).done();

        internalKafkaClient.setPodName(kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName());
        int sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        int received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 30, "TLS", "consumer-group-certs-91");
        assertThat(received, is(30));

        SecretUtils.createCustomSecret(customCertServer1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.key").getFile());

        SecretUtils.createCustomSecret(customCertServer2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.key").getFile());

        kafkaSnapshot = StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 40, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(40));

        sent = internalKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "TLS");
        assertThat(sent, is(10));
        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 50, "TLS", "consumer-group-certs-92");
        assertThat(received, is(50));

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(new KafkaListenerExternalNodePortBuilder()
                .withTls(true)
                .build());
        });

        StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaSnapshot);
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);

        externalBasicKafkaClient.setCaCertName(null);
        producer = externalBasicKafkaClient.sendMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 10, "SSL");
        consumer = externalBasicKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "SSL");

        assertThat("Producer didn't produce all messages", producer.get(1, TimeUnit.MINUTES), is(10));
        assertThat("Consumer didn't consume all messages", consumer.get(1, TimeUnit.MINUTES), is(60));

        received = internalKafkaClient.receiveMessagesTls(topicName, NAMESPACE, CLUSTER_NAME, userName, 60, "TLS", "consumer-group-certs-93");
        assertThat(received, is(60));
    }

    @Test
    void testNonExistingCustomCertificate() {
        String nonExistingCertName = "non-existing-certificate";
        String clusterName = "broken-cluster";

        KafkaResource.kafkaWithoutWait(KafkaResource.defaultKafka(clusterName, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(nonExistingCertName)
                                    .withKey("ca.key")
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().build());

        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.zookeeperStatefulSetName(clusterName), 1);

        KafkaUtils.waitUntilKafkaStatusConditionContainsMessage(clusterName, NAMESPACE, "Secret " + nonExistingCertName + ".*does not exist.*");
    }

    @Test
    void testCertificateWithNonExistingDataCrt() {
        String nonExistingCertName = "non-existing-crt";
        String clusterName = "broken-cluster";

        KafkaResource.kafkaWithoutWait(KafkaResource.defaultKafka(clusterName, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey("ca.key")
                                    .withCertificate(nonExistingCertName)
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().build());

        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.zookeeperStatefulSetName(clusterName), 1);

        KafkaUtils.waitUntilKafkaStatusConditionContainsMessage(clusterName, NAMESPACE,
                "Secret " + customCertServer1 + ".*does not contain certificate under the key " + nonExistingCertName + ".*");
    }

    @Test
    void testCertificateWithNonExistingDataKey() {
        String nonExistingCertKey = "non-existing-key";
        String clusterName = "broken-cluster";

        KafkaResource.kafkaWithoutWait(KafkaResource.defaultKafka(clusterName, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewConfiguration()
                                .withNewBrokerCertChainAndKey()
                                    .withSecretName(customCertServer1)
                                    .withKey(nonExistingCertKey)
                                    .withCertificate("ca.crt")
                                .endBrokerCertChainAndKey()
                            .endConfiguration()
                        .endTls()
                    .endListeners()
                .endKafka()
            .endSpec().build());

        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.zookeeperStatefulSetName(clusterName), 1);

        KafkaUtils.waitUntilKafkaStatusConditionContainsMessage(clusterName, NAMESPACE,
                "Secret " + customCertServer1 + ".*does not contain.*private key under the key " + nonExistingCertKey + ".*");
    }

    @BeforeEach
    void setupCertificates() {
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customCertChain1).delete();
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customCertChain2).delete();
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customCertServer1).delete();
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customCertServer2).delete();
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customRootCA1).delete();
        kubeClient().getClient().secrets().inNamespace(NAMESPACE).withName(customRootCA2).delete();

        SecretUtils.createCustomSecret(customCertChain1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/chain/strimzi-bundle.crt").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/chain/strimzi-key.pem").getFile());

        SecretUtils.createCustomSecret(customCertChain2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/chain/strimzi-bundle.crt").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/chain/strimzi-key.pem").getFile());

        SecretUtils.createCustomSecret(customCertServer1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/strimzi/strimzi.key").getFile());

        SecretUtils.createCustomSecret(customCertServer2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/strimzi/strimzi.key").getFile());

        SecretUtils.createCustomSecret(customRootCA1, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver1/root/ca.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver1/root/ca.key").getFile());

        SecretUtils.createCustomSecret(customRootCA2, CLUSTER_NAME, NAMESPACE,
                getClass().getClassLoader().getResource("custom-certs/ver2/root/ca.pem").getFile(),
                getClass().getClassLoader().getResource("custom-certs/ver2/root/ca.key").getFile());

        externalBasicKafkaClient.setCaCertName(null);
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();

        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE).done();
        cluster.setNamespace(NAMESPACE);
    }

    @Override
    protected void tearDownEnvironmentAfterEach() throws Exception {
        super.tearDownEnvironmentAfterEach();
        kubeClient().getClient().persistentVolumeClaims().inNamespace(NAMESPACE).delete();
    }
}

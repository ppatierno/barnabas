/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.security.oauth;

import io.strimzi.api.kafka.model.KafkaBridgeResources;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpecBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMaker2Resource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.kafkaclients.KafkaBridgeClientsResource;
import io.strimzi.systemtest.resources.crd.kafkaclients.KafkaOauthClientsResource;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectorUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.JobUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.WaitException;
import io.vertx.core.cli.annotations.Description;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.strimzi.systemtest.Constants.BRIDGE;
import static io.strimzi.systemtest.Constants.CONNECT;
import static io.strimzi.systemtest.Constants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.Constants.HTTP_BRIDGE_DEFAULT_PORT;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER2;
import static io.strimzi.systemtest.Constants.OAUTH;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

@Tag(OAUTH)
@Tag(REGRESSION)
public class OauthPlainST extends OauthAbstractST {
    protected static final Logger LOGGER = LogManager.getLogger(OauthPlainST.class);

    private KafkaOauthClientsResource oauthInternalClientJob;

    @Description(
            "As an oauth producer, I should be able to produce messages to the kafka broker\n" +
            "As an oauth consumer, I should be able to consumer messages from the kafka broker.")
    @Test
    void testProducerConsumer() {
        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);
    }

    @Description("As an oauth KafkaConnect, I should be able to sink messages from kafka broker topic.")
    @Test
    @Tag(CONNECT)
    @Tag(CONNECT_COMPONENTS)
    void testProducerConsumerConnect() {
        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);

        KafkaClientsResource.deployKafkaClients(false, KAFKA_CLIENTS_NAME).done();

        KafkaConnectResource.kafkaConnect(CLUSTER_NAME, 1)
                .withNewSpec()
                    .withReplicas(1)
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(CLUSTER_NAME))
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                        .withClientId("kafka-connect")
                        .withNewClientSecret()
                            .withSecretName(CONNECT_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                    .endKafkaClientAuthenticationOAuth()
                    .withTls(null)
                .endSpec()
                .done();

        String kafkaConnectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        KafkaConnectorUtils.createFileSinkConnector(kafkaConnectPodName, TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, "http://localhost:8083");

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH);
    }

    @Description("As an oauth mirror maker, I should be able to replicate topic data between kafka clusters")
    @Test
    @Tag(MIRROR_MAKER)
    void testProducerConsumerMirrorMaker() {
        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);

        String targetKafkaCluster = CLUSTER_NAME + "-target";

        KafkaResource.kafkaEphemeral(targetKafkaCluster, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .addNewGenericKafkaListener()
                            .withName("plain")
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .endKafkaListenerAuthenticationOAuth()
                        .endGenericKafkaListener()
                        .addNewGenericKafkaListener()
                            .withName("external")
                            .withPort(9094)
                            .withType(KafkaListenerType.NODEPORT)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .endKafkaListenerAuthenticationOAuth()
                        .endGenericKafkaListener()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, CLUSTER_NAME, targetKafkaCluster,
                ClientUtils.generateRandomConsumerGroup(), 1, false)
                .editSpec()
                    .withNewConsumer()
                        .withBootstrapServers(KafkaResources.plainBootstrapAddress(CLUSTER_NAME))
                        .withGroupId(ClientUtils.generateRandomConsumerGroup())
                        .addToConfig(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                        .withNewKafkaClientAuthenticationOAuth()
                            .withNewTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                            .withClientId("kafka-mirror-maker")
                            .withNewClientSecret()
                                .withSecretName(MIRROR_MAKER_OAUTH_SECRET)
                                .withKey(OAUTH_KEY)
                            .endClientSecret()
                        .endKafkaClientAuthenticationOAuth()
                        .withTls(null)
                    .endConsumer()
                    .withNewProducer()
                        .withBootstrapServers(KafkaResources.plainBootstrapAddress(targetKafkaCluster))
                        .withNewKafkaClientAuthenticationOAuth()
                            .withNewTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                            .withClientId("kafka-mirror-maker")
                            .withNewClientSecret()
                                .withSecretName(MIRROR_MAKER_OAUTH_SECRET)
                                .withKey(OAUTH_KEY)
                            .endClientSecret()
                        .endKafkaClientAuthenticationOAuth()
                        .addToConfig(ProducerConfig.ACKS_CONFIG, "all")
                        .withTls(null)
                    .endProducer()
                .endSpec()
                .done();

        TestUtils.waitFor("Waiting for Mirror Maker will copy messages from " + CLUSTER_NAME + " to " + targetKafkaCluster,
            Constants.GLOBAL_CLIENTS_POLL, Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> {
                LOGGER.info("Deleting the Job");
                JobUtils.deleteJobWithWait(NAMESPACE, OAUTH_CONSUMER_NAME);

                LOGGER.info("Creating new client with new consumer-group and also to point on {} cluster", targetKafkaCluster);
                KafkaOauthClientsResource kafkaOauthClientJob = new KafkaOauthClientsResource(OAUTH_PRODUCER_NAME, OAUTH_CONSUMER_NAME,
                    KafkaResources.plainBootstrapAddress(targetKafkaCluster), TOPIC_NAME, MESSAGE_COUNT, "", ClientUtils.generateRandomConsumerGroup(),
                    OAUTH_CLIENT_NAME, OAUTH_CLIENT_SECRET, keycloakInstance.getOauthTokenEndpointUri());

                kafkaOauthClientJob.consumerStrimziOauthPlain().done();

                try {
                    ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);
                    return  true;
                } catch (WaitException e) {
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @Test
    @Tag(MIRROR_MAKER2)
    @Tag(CONNECT_COMPONENTS)
    void testProducerConsumerMirrorMaker2() {
        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);

        String kafkaSourceClusterName = CLUSTER_NAME;
        String kafkaTargetClusterName = CLUSTER_NAME + "-target";
        // mirror maker 2 adding prefix to mirrored topic for in this case mirrotopic will be : my-cluster.my-topic
        String kafkaTargetClusterTopicName = kafkaSourceClusterName + "." + TOPIC_NAME;
    
        KafkaResource.kafkaEphemeral(kafkaTargetClusterName, 3, 1)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .addNewGenericKafkaListener()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .withNewKafkaListenerAuthenticationOAuth()
                                    .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                    .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                    .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                    .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                    .withUserNameClaim(keycloakInstance.getUserNameClaim())
                                .endKafkaListenerAuthenticationOAuth()
                            .endGenericKafkaListener()
                            .addNewGenericKafkaListener()
                                .withName("external")
                                .withPort(9094)
                                .withType(KafkaListenerType.NODEPORT)
                                .withTls(false)
                                .withNewKafkaListenerAuthenticationOAuth()
                                    .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                    .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                    .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                    .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                    .withUserNameClaim(keycloakInstance.getUserNameClaim())
                                .endKafkaListenerAuthenticationOAuth()
                            .endGenericKafkaListener()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .done();

        // Deploy Mirror Maker 2.0 with oauth
        KafkaMirrorMaker2ClusterSpec sourceClusterWithOauth = new KafkaMirrorMaker2ClusterSpecBuilder()
                .withAlias(kafkaSourceClusterName)
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(kafkaSourceClusterName))
                .withNewKafkaClientAuthenticationOAuth()
                    .withNewTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId("kafka-mirror-maker-2")
                    .withNewClientSecret()
                        .withSecretName(MIRROR_MAKER_2_OAUTH_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                .endKafkaClientAuthenticationOAuth()
                .build();

        KafkaMirrorMaker2ClusterSpec targetClusterWithOauth = new KafkaMirrorMaker2ClusterSpecBuilder()
                .withAlias(kafkaTargetClusterName)
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(kafkaTargetClusterName))
                .withNewKafkaClientAuthenticationOAuth()
                    .withNewTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId("kafka-mirror-maker-2")
                    .withNewClientSecret()
                        .withSecretName(MIRROR_MAKER_2_OAUTH_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                .endKafkaClientAuthenticationOAuth()
                .build();
        
        KafkaMirrorMaker2Resource.kafkaMirrorMaker2(CLUSTER_NAME, kafkaTargetClusterName, kafkaSourceClusterName, 1, false)
                .editSpec()
                    .withClusters(sourceClusterWithOauth, targetClusterWithOauth)
                    .editFirstMirror()
                        .withNewSourceCluster(kafkaSourceClusterName)
                    .endMirror()
                .endSpec()
                .done();

        TestUtils.waitFor("Waiting for Mirror Maker 2 will copy messages from " + kafkaSourceClusterName + " to " + kafkaTargetClusterName,
            Duration.ofSeconds(30).toMillis(), Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> {
                LOGGER.info("Deleting the Job {}", OAUTH_CONSUMER_NAME);
                JobUtils.deleteJobWithWait(NAMESPACE, OAUTH_CONSUMER_NAME);

                LOGGER.info("Creating new client with new consumer-group and also to point on {} cluster", kafkaTargetClusterName);
                KafkaOauthClientsResource kafkaOauthClientJob = new KafkaOauthClientsResource(OAUTH_PRODUCER_NAME, OAUTH_CONSUMER_NAME,
                    KafkaResources.plainBootstrapAddress(kafkaTargetClusterName), kafkaTargetClusterTopicName, MESSAGE_COUNT, "",
                    ClientUtils.generateRandomConsumerGroup(), OAUTH_CLIENT_NAME, OAUTH_CLIENT_SECRET, keycloakInstance.getOauthTokenEndpointUri());

                kafkaOauthClientJob.consumerStrimziOauthPlain().done();

                try {
                    ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);
                    return  true;
                } catch (WaitException e) {
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @Description("As a oauth bridge, I should be able to send messages to bridge endpoint.")
    @Test
    @Tag(BRIDGE)
    void testProducerConsumerBridge() {
        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);

        KafkaClientsResource.deployKafkaClients(false, KAFKA_CLIENTS_NAME).done();

        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.plainBootstrapAddress(CLUSTER_NAME), 1)
                .editSpec()
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                        .withClientId("kafka-bridge")
                        .withNewClientSecret()
                            .withSecretName(BRIDGE_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                    .endKafkaClientAuthenticationOAuth()
                .endSpec()
                .done();

        String producerName = "bridge-producer";

        KafkaBridgeClientsResource kafkaBridgeClientJob = new KafkaBridgeClientsResource(producerName, "", KafkaBridgeResources.serviceName(CLUSTER_NAME),
            TOPIC_NAME, MESSAGE_COUNT, "", ClientUtils.generateRandomConsumerGroup(), HTTP_BRIDGE_DEFAULT_PORT, 1000, 1000);

        kafkaBridgeClientJob.producerStrimziBridge().done();
        ClientUtils.waitForClientSuccess(producerName, NAMESPACE, MESSAGE_COUNT);
    }

    @Test
    void testIntrospectionEndpointWithPlainCommunication() {
        LOGGER.info("Deploying kafka...");

        keycloakInstance.setIntrospectionEndpointUri("http://" + keycloakInstance.getHttpUri() + "/auth/realms/internal/protocol/openid-connect/token/introspect");
        String introspectionKafka = CLUSTER_NAME + "-intro";

        KafkaResource.kafkaEphemeral(introspectionKafka, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .addNewGenericKafkaListener()
                            .withName("tls")
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withClientId(OAUTH_KAFKA_CLIENT_NAME)
                                .withNewClientSecret()
                                    .withSecretName(OAUTH_KAFKA_CLIENT_SECRET)
                                    .withKey(OAUTH_KEY)
                                .endClientSecret()
                                .withAccessTokenIsJwt(false)
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withIntrospectionEndpointUri(keycloakInstance.getIntrospectionEndpointUri())
                            .endKafkaListenerAuthenticationOAuth()
                        .endGenericKafkaListener()
                        .addNewGenericKafkaListener()
                            .withName("external")
                            .withPort(9094)
                            .withType(KafkaListenerType.NODEPORT)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withClientId(OAUTH_KAFKA_CLIENT_NAME)
                                .withNewClientSecret()
                                    .withSecretName(OAUTH_KAFKA_CLIENT_SECRET)
                                    .withKey(OAUTH_KEY)
                                .endClientSecret()
                                .withAccessTokenIsJwt(false)
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withIntrospectionEndpointUri(keycloakInstance.getIntrospectionEndpointUri())
                            .endKafkaListenerAuthenticationOAuth()
                        .endGenericKafkaListener()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        oauthInternalClientJob.producerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_PRODUCER_NAME, NAMESPACE, MESSAGE_COUNT);

        oauthInternalClientJob.consumerStrimziOauthPlain().done();
        ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, NAMESPACE, MESSAGE_COUNT);
    }

    @BeforeEach
    void setUpEach() {
        oauthInternalClientJob = new KafkaOauthClientsResource(oauthInternalClientJob);
    }

    @BeforeAll
    void setUp() {
        keycloakInstance.setRealm("internal", false);

        LOGGER.info("Setting producer and consumer properties");

        oauthInternalClientJob = new KafkaOauthClientsResource(OAUTH_PRODUCER_NAME, OAUTH_CONSUMER_NAME,
            KafkaResources.plainBootstrapAddress(CLUSTER_NAME), TOPIC_NAME, MESSAGE_COUNT, "",
            ClientUtils.generateRandomConsumerGroup(), OAUTH_CLIENT_NAME, OAUTH_CLIENT_SECRET, keycloakInstance.getOauthTokenEndpointUri());

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 1)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .addNewGenericKafkaListener()
                            .withName("plain")
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .endKafkaListenerAuthenticationOAuth()
                        .endGenericKafkaListener()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        KafkaTopicResource.topic(CLUSTER_NAME, TOPIC_NAME).done();
    }
}

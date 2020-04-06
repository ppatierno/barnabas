/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.oauth;

import io.fabric8.kubernetes.api.model.Service;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpecBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalNodePort;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalNodePortBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerPlainBuilder;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.kafkaclients.externalClients.OauthExternalKafkaClient;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaBridgeUtils;
import io.strimzi.systemtest.utils.HttpUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.ServiceUtils;
import io.strimzi.test.TestUtils;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.core.cli.annotations.Description;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMaker2Resource;
import io.strimzi.systemtest.resources.crd.KafkaMirrorMakerResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.strimzi.api.kafka.model.KafkaResources.kafkaStatefulSetName;
import static io.strimzi.systemtest.Constants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.CONNECT;
import static io.strimzi.systemtest.Constants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER2;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.OAUTH;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

@Tag(OAUTH)
@Tag(REGRESSION)
@Tag(NODEPORT_SUPPORTED)
@Tag(EXTERNAL_CLIENTS_USED)
public class OauthPlainST extends OauthBaseST {

    private OauthExternalKafkaClient oauthExternalKafkaClient;

    @Description(
            "As an oauth producer, I should be able to produce messages to the kafka broker\n" +
            "As an oauth consumer, I should be able to consumer messages from the kafka broker.")
    @Test
    void testProducerConsumer() throws InterruptedException, ExecutionException, TimeoutException {
        oauthExternalKafkaClient.setClusterName(CLUSTER_NAME);

        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
    }

    @Description("As an oauth kafka connect, I should be able to sink messages from kafka broker topic.")
    @Test
    @Tag(CONNECT)
    @Tag(CONNECT_COMPONENTS)
    void testProducerConsumerConnect() throws InterruptedException, ExecutionException, TimeoutException {
        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));

        KafkaClientsResource.deployKafkaClients(false, KAFKA_CLIENTS_NAME).done();

        KafkaConnectResource.kafkaConnect(CLUSTER_NAME, 1)
                .editMetadata()
                    .addToLabels("type", "kafka-connect")
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(CLUSTER_NAME))
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(oauthTokenEndpointUri)
                        .withClientId("kafka-connect")
                        .withNewClientSecret()
                            .withSecretName(CONNECT_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                    .endKafkaClientAuthenticationOAuth()
                    .withTls(null)
                .endSpec()
                .done();

        String kafkaConnectPodName = kubeClient().listPods("type", "kafka-connect").get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        KafkaConnectUtils.createFileSinkConnector(kafkaConnectPodName, TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, "http://localhost:8083");

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH);
    }

    @Description("As an oauth mirror maker, I should be able to replicate topic data between kafka clusters")
    @Test
    @Tag(MIRROR_MAKER)
    void testProducerConsumerMirrorMaker() throws InterruptedException, ExecutionException, TimeoutException {
        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));

        String targetKafkaCluster = CLUSTER_NAME + "-target";

        KafkaResource.kafkaEphemeral(targetKafkaCluster, 1, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewPlain()
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(validIssuerUri)
                                .withJwksEndpointUri(jwksEndpointUri)
                                .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                                .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                                .withUserNameClaim(userNameClaim)
                            .endKafkaListenerAuthenticationOAuth()
                        .endPlain()
                        .withNewKafkaListenerExternalNodePort()
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(validIssuerUri)
                                .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                                .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                                .withJwksEndpointUri(jwksEndpointUri)
                                .withUserNameClaim(userNameClaim)
                            .endKafkaListenerAuthenticationOAuth()
                            .withTls(false)
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        KafkaMirrorMakerResource.kafkaMirrorMaker(CLUSTER_NAME, CLUSTER_NAME, targetKafkaCluster,
                "my-group" + new Random().nextInt(Integer.MAX_VALUE), 1, false)
                .editSpec()
                    .withNewConsumer()
                        .withBootstrapServers(KafkaResources.plainBootstrapAddress(CLUSTER_NAME))
                        .withGroupId("my-group" +  new Random().nextInt(Integer.MAX_VALUE))
                        .addToConfig(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                        .withNewKafkaClientAuthenticationOAuth()
                            .withNewTokenEndpointUri(oauthTokenEndpointUri)
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
                            .withNewTokenEndpointUri(oauthTokenEndpointUri)
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
                try {
                    oauthExternalKafkaClient.setConsumerGroup(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));
                    Future<Integer> oauthConsumer = oauthExternalKafkaClient.receiveMessagesPlain();
                    return MESSAGE_COUNT == oauthConsumer.get(Constants.GLOBAL_CLIENTS_POLL, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    LOGGER.error("Consumer doesn't received messages {}", e.getMessage());
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @Test
    @Tag(MIRROR_MAKER2)
    @Tag(CONNECT_COMPONENTS)
    void testProducerConsumerMirrorMaker2() throws InterruptedException, ExecutionException, TimeoutException {
        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));

        String kafkaSourceClusterName = CLUSTER_NAME;
        String kafkaTargetClusterName = CLUSTER_NAME + "-target";
        // mirror maker 2 adding prefix to mirrored topic for in this case mirrotopic will be : my-cluster.my-topic
        String kafkaTargetClusterTopicName = kafkaSourceClusterName + "." + TOPIC_NAME;
    
        KafkaResource.kafkaEphemeral(kafkaTargetClusterName, 3, 1)
                .editSpec()
                    .editKafka()
                        .editListeners()
                            .withNewPlain()
                                .withNewKafkaListenerAuthenticationOAuth()
                                    .withValidIssuerUri(validIssuerUri)
                                    .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                                    .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                                    .withJwksEndpointUri(jwksEndpointUri)
                                    .withUserNameClaim(userNameClaim)
                                .endKafkaListenerAuthenticationOAuth()
                            .endPlain()
                            .withNewKafkaListenerExternalNodePort()
                                .withNewKafkaListenerAuthenticationOAuth()
                                    .withValidIssuerUri(validIssuerUri)
                                    .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                                    .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                                    .withJwksEndpointUri(jwksEndpointUri)
                                    .withUserNameClaim(userNameClaim)
                                .endKafkaListenerAuthenticationOAuth()
                                .withTls(false)
                            .endKafkaListenerExternalNodePort()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .done();

        // Deploy Mirror Maker 2.0 with oauth
        KafkaMirrorMaker2ClusterSpec sourceClusterWithOauth = new KafkaMirrorMaker2ClusterSpecBuilder()
                .withAlias(kafkaSourceClusterName)
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(kafkaSourceClusterName))
                .withNewKafkaClientAuthenticationOAuth()
                    .withNewTokenEndpointUri(oauthTokenEndpointUri)
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
                    .withNewTokenEndpointUri(oauthTokenEndpointUri)
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
                try {
                    oauthExternalKafkaClient.setConsumerGroup(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));
                    Future<Integer> oauthConsumer = oauthExternalKafkaClient.receiveMessagesPlain();
                    return MESSAGE_COUNT == oauthConsumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    LOGGER.error("Consumer doesn't received messages {}", e.getMessage());
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @Description("As a oauth bridge, I should be able to send messages to bridge endpoint.")
    @Test
    void testProducerConsumerBridge(Vertx vertx) throws InterruptedException, ExecutionException, TimeoutException {
        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));

        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.plainBootstrapAddress(CLUSTER_NAME), 1)
                .editSpec()
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(oauthTokenEndpointUri)
                        .withClientId("kafka-bridge")
                        .withNewClientSecret()
                            .withSecretName(BRIDGE_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                    .endKafkaClientAuthenticationOAuth()
                .endSpec()
                .done();

        Service bridgeService = KubernetesResource.deployBridgeNodePortService(BRIDGE_EXTERNAL_SERVICE, NAMESPACE, CLUSTER_NAME);
        KubernetesResource.createServiceResource(bridgeService, NAMESPACE);

        ServiceUtils.waitForNodePortService(bridgeService.getMetadata().getName());

        client = WebClient.create(vertx, new WebClientOptions().setSsl(false));

        JsonObject obj = new JsonObject();
        obj.put("key", "my-key");

        JsonArray records = new JsonArray();

        JsonObject firstLead = new JsonObject();
        firstLead.put("key", "my-key");
        firstLead.put("value", "sales-lead-0001");

        JsonObject secondLead = new JsonObject();
        secondLead.put("value", "sales-lead-0002");

        JsonObject thirdLead = new JsonObject();
        thirdLead.put("value", "sales-lead-0003");

        records.add(firstLead);
        records.add(secondLead);
        records.add(thirdLead);

        JsonObject root = new JsonObject();
        root.put("records", records);

        JsonObject response = HttpUtils.sendMessagesHttpRequest(root, clusterHost,
                KafkaBridgeUtils.getBridgeNodePort(NAMESPACE, BRIDGE_EXTERNAL_SERVICE), TOPIC_NAME, client);

        response.getJsonArray("offsets").forEach(object -> {
            if (object instanceof JsonObject) {
                JsonObject item = (JsonObject) object;
                LOGGER.info("Offset number is {}", item.getInteger("offset"));
                int exceptedValue = 0;
                assertThat("Offset is not zero", item.getInteger("offset"), greaterThan(exceptedValue));
            }
        });
    }

    @Test
    void testIntrospectionEndpointWithPlainCommunication() throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.info("Deploying kafka...");

        String introspectionKafka = CLUSTER_NAME + "-intro";

        KafkaResource.kafkaEphemeral(introspectionKafka, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withClientId(OAUTH_KAFKA_CLIENT_NAME)
                                .withNewClientSecret()
                                    .withSecretName(OAUTH_KAFKA_CLIENT_SECRET)
                                    .withKey(OAUTH_KEY)
                                .endClientSecret()
                                .withAccessTokenIsJwt(false)
                                .withValidIssuerUri(validIssuerUri)
                                .withIntrospectionEndpointUri(introspectionEndpointUri)
                            .endKafkaListenerAuthenticationOAuth()
                            .withTls(false)
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        oauthExternalKafkaClient.setClusterName(introspectionKafka);

        Future<Integer> producer = oauthExternalKafkaClient.sendMessagesPlain();
        Future<Integer> consumer = oauthExternalKafkaClient.receiveMessagesPlain();

        assertThat(producer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
        assertThat(consumer.get(Constants.GLOBAL_CLIENTS_TIMEOUT, TimeUnit.MILLISECONDS), is(MESSAGE_COUNT));
    }

    @BeforeEach
    void setUpEach() {
        oauthExternalKafkaClient.setConsumerGroup(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));
    }

    @BeforeAll
    void setUp() {
        LOGGER.info("Replacing validIssuerUri: {} to pointing to internal realm", validIssuerUri);
        LOGGER.info("Replacing jwksEndpointUri: {} to pointing to internal realm", jwksEndpointUri);
        LOGGER.info("Replacing oauthTokenEndpointUri: {} to pointing to internal realm", oauthTokenEndpointUri);

        validIssuerUri = "http://" + keycloakIpWithPortHttp + "/auth/realms/internal";
        jwksEndpointUri = "http://" + keycloakIpWithPortHttp + "/auth/realms/internal/protocol/openid-connect/certs";
        oauthTokenEndpointUri = "http://" + keycloakIpWithPortHttp + "/auth/realms/internal/protocol/openid-connect/token";
        introspectionEndpointUri = "http://" + keycloakIpWithPortHttp + "/auth/realms/internal/protocol/openid-connect/token/introspect";

        LOGGER.info("Setting producer and consumer properties");

        oauthExternalKafkaClient = new OauthExternalKafkaClient.Builder()
            .withTopicName(TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(CLUSTER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withConsumerGroupName(CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE))
            .withOauthClientId(OAUTH_CLIENT_NAME)
            .withClientSecretName(OAUTH_CLIENT_SECRET)
            .withOauthTokenEndpointUri(oauthTokenEndpointUri)
            .build();

        LOGGER.info("Oauth kafka client has following settings {}", oauthExternalKafkaClient.toString());

        String kafkaName = KafkaResources.kafkaStatefulSetName(CLUSTER_NAME);
        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(kafkaName);

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            // internal plain
            kafka.getSpec().getKafka().getListeners().setPlain(
                new KafkaListenerPlainBuilder()
                    .withNewKafkaListenerAuthenticationOAuth()
                        .withValidIssuerUri(validIssuerUri)
                        .withJwksEndpointUri(jwksEndpointUri)
                        .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                        .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                        .withUserNameClaim(userNameClaim)
                    .endKafkaListenerAuthenticationOAuth()
                    .build());

            // external
            kafka.getSpec().getKafka().getListeners().setExternal(
                new KafkaListenerExternalNodePortBuilder()
                    .withNewKafkaListenerAuthenticationOAuth()
                        .withValidIssuerUri(validIssuerUri)
                        .withJwksEndpointUri(jwksEndpointUri)
                        .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                        .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                        .withUserNameClaim(userNameClaim)
                    .endKafkaListenerAuthenticationOAuth()
                    .build());

            ((KafkaListenerExternalNodePort) kafka.getSpec().getKafka().getListeners().getExternal()).setTls(false);
        });

        StatefulSetUtils.waitTillSsHasRolled(kafkaStatefulSetName(CLUSTER_NAME), 3, kafkaPods);
    }
}

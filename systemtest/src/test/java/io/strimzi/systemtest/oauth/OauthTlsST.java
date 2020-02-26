/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.oauth;

import io.fabric8.kubernetes.api.model.Service;
import io.strimzi.api.kafka.model.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.KafkaListenerExternalNodePortBuilder;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.kafkaclients.ClientFactory;
import io.strimzi.systemtest.kafkaclients.EClientType;
import io.strimzi.systemtest.kafkaclients.externalClients.OauthKafkaClient;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaBridgeUtils;
import io.strimzi.systemtest.utils.HttpUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.ServiceUtils;
import io.strimzi.test.TimeoutException;
import io.vertx.core.Vertx;
import io.vertx.core.cli.annotations.Description;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.crd.KafkaBridgeResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;

import java.io.IOException;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
public class OauthTlsST extends OauthBaseST {

    private OauthKafkaClient oauthKafkaClient = (OauthKafkaClient) ClientFactory.getClient(EClientType.OAUTH);

    @Description(
            "As an oauth producer, i am able to produce messages to the kafka broker\n" +
            "As an oauth consumer, i am able to consumer messages from the kafka broker using encrypted communication")
    @Test
    void testProducerConsumer() throws IOException, KeyStoreException, InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        Future<Integer> producer = oauthKafkaClient.sendMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL");

        Future<Integer> consumer = oauthKafkaClient.receiveMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL", CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));

        assertThat(producer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
        assertThat(consumer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
    }

    @Description("As an oauth kafka connect, i am able to sink messages from kafka broker topic using encrypted communication.")
    @Test
    void testProducerConsumerConnect() throws IOException, KeyStoreException, InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        Future<Integer> producer = oauthKafkaClient.sendMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL");

        Future<Integer> consumer = oauthKafkaClient.receiveMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL", CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));

        assertThat(producer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
        assertThat(consumer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));

        KafkaConnectResource.kafkaConnect(CLUSTER_NAME, 1)
                .editMetadata()
                    .addToLabels("type", "kafka-connect")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(oauthTokenEndpointUri)
                        .withClientId("kafka-connect")
                        .withNewClientSecret()
                        .withSecretName("my-connect-oauth")
                        .withKey(OAUTH_KEY)
                        .endClientSecret()
                        .withTlsTrustedCertificates(
                            new CertSecretSourceBuilder()
                                .withSecretName(SECRET_OF_KEYCLOAK)
                                .withCertificate(CERTIFICATE_OF_KEYCLOAK)
                                .build())
                        .withDisableTlsHostnameVerification(true)
                    .endKafkaClientAuthenticationOAuth()
                    .withNewTls()
                        .addNewTrustedCertificate()
                            .withSecretName(CLUSTER_NAME + "-cluster-ca-cert")
                            .withCertificate("ca.crt")
                        .endTrustedCertificate()
                    .endTls()
                    .withBootstrapServers(CLUSTER_NAME + "-kafka-bootstrap:9093")
                .endSpec()
                .done();

        String kafkaConnectPodName = kubeClient().listPods("type", "kafka-connect").get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        KafkaConnectUtils.createFileSinkConnector(KafkaResources.kafkaPodName(CLUSTER_NAME, 0), TOPIC_NAME, Constants.DEFAULT_SINK_FILE_NAME, KafkaConnectResources.url(CLUSTER_NAME, NAMESPACE, 8083));

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_NAME);
    }

    @Description("As a oauth bridge, i am able to send messages to bridge endpoint using encrypted communication")
    @Test
    void testProducerConsumerBridge(Vertx vertx) throws InterruptedException, TimeoutException, ExecutionException, java.util.concurrent.TimeoutException, IOException, KeyStoreException {
        Future<Integer> producer = oauthKafkaClient.sendMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL");

        Future<Integer> consumer = oauthKafkaClient.receiveMessagesTls(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, OAUTH_CLIENT_NAME,
                MESSAGE_COUNT, "SSL", CONSUMER_GROUP_NAME + "-" + rng.nextInt(Integer.MAX_VALUE));

        assertThat(producer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
        assertThat(consumer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));

        KafkaBridgeResource.kafkaBridge(CLUSTER_NAME, KafkaResources.tlsBootstrapAddress(CLUSTER_NAME), 1)
                .editSpec()
                    .withNewTls()
                        .withTrustedCertificates(
                            new CertSecretSourceBuilder()
                                .withCertificate("ca.crt")
                                .withSecretName(KafkaResources.clusterCaCertificateSecretName(CLUSTER_NAME)).build())
                    .endTls()
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(oauthTokenEndpointUri)
                        .withClientId("kafka-bridge")
                        .withNewClientSecret()
                            .withSecretName(BRIDGE_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                        .addNewTlsTrustedCertificate()
                            .withSecretName(SECRET_OF_KEYCLOAK)
                            .withCertificate(CERTIFICATE_OF_KEYCLOAK)
                        .endTlsTrustedCertificate()
                        .withDisableTlsHostnameVerification(true)
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

    @BeforeAll
    void setUp() {
        LOGGER.info("Replacing validIssuerUri: {} to pointing to internal realm", validIssuerUri);
        LOGGER.info("Replacing jwksEndpointUri: {} to pointing to internal realm", jwksEndpointUri);
        LOGGER.info("Replacing oauthTokenEndpointUri: {} to pointing to internal realm", oauthTokenEndpointUri);

        validIssuerUri = "https://" + keycloakIpWithPortHttps + "/auth/realms/internal";
        jwksEndpointUri = "https://" + keycloakIpWithPortHttps + "/auth/realms/internal/protocol/openid-connect/certs";
        oauthTokenEndpointUri = "https://" + keycloakIpWithPortHttps + "/auth/realms/internal/protocol/openid-connect/token";

        oauthKafkaClient.setClientId(OAUTH_CLIENT_NAME);
        oauthKafkaClient.setClientSecretName(OAUTH_CLIENT_SECRET);
        oauthKafkaClient.setOauthTokenEndpointUri(oauthTokenEndpointUri);

        LOGGER.info("Oauth kafka client has following settings {}", oauthKafkaClient.toString());

        KafkaResource.replaceKafkaResource(CLUSTER_NAME, kafka -> {
            kafka.getSpec().getKafka().getListeners().setExternal(
                new KafkaListenerExternalNodePortBuilder()
                    .withNewKafkaListenerAuthenticationOAuth()
                        .withValidIssuerUri(validIssuerUri)
                        .withJwksEndpointUri(jwksEndpointUri)
                        .withJwksExpirySeconds(JWKS_EXPIRE_SECONDS)
                        .withJwksRefreshSeconds(JWKS_REFRESH_SECONDS)
                        .withUserNameClaim(userNameClaim)
                        .withTlsTrustedCertificates(
                            new CertSecretSourceBuilder()
                                .withSecretName(SECRET_OF_KEYCLOAK)
                                .withCertificate(CERTIFICATE_OF_KEYCLOAK)
                                .build())
                        .withDisableTlsHostnameVerification(true)
                    .endKafkaListenerAuthenticationOAuth()
                    .build());
        });

        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME), 3);
    }
}




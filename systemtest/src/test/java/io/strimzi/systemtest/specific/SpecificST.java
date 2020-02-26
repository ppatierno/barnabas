/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.specific;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.LoadBalancerListenerBootstrapOverride;
import io.strimzi.api.kafka.model.listener.LoadBalancerListenerBootstrapOverrideBuilder;
import io.strimzi.api.kafka.model.listener.LoadBalancerListenerBrokerOverride;
import io.strimzi.api.kafka.model.listener.LoadBalancerListenerBrokerOverrideBuilder;
import io.strimzi.systemtest.BaseST;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.strimzi.systemtest.Constants.LOADBALANCER_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.SPECIFIC;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;

@Tag(SPECIFIC)
public class SpecificST extends BaseST {

    private static final Logger LOGGER = LogManager.getLogger(SpecificST.class);
    public static final String NAMESPACE = "specific-cluster-test";

    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    void testRackAware() throws Exception {
        String rackKey = "rack-key";
        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewRack()
                        .withTopologyKey(rackKey)
                    .endRack()
                .editListeners()
                    .withNewKafkaListenerExternalLoadBalancer()
                        .withTls(false)
                    .endKafkaListenerExternalLoadBalancer()
                .endListeners()
                .endKafka()
            .endSpec().done();

        Affinity kafkaPodSpecAffinity = kubeClient().getStatefulSet(KafkaResources.kafkaStatefulSetName(CLUSTER_NAME)).getSpec().getTemplate().getSpec().getAffinity();
        NodeSelectorRequirement kafkaPodNodeSelectorRequirement = kafkaPodSpecAffinity.getNodeAffinity()
                .getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms().get(0).getMatchExpressions().get(0);

        assertThat(kafkaPodNodeSelectorRequirement.getKey(), is(rackKey));
        assertThat(kafkaPodNodeSelectorRequirement.getOperator(), is("Exists"));

        PodAffinityTerm kafkaPodAffinityTerm = kafkaPodSpecAffinity.getPodAntiAffinity().getPreferredDuringSchedulingIgnoredDuringExecution().get(0).getPodAffinityTerm();

        assertThat(kafkaPodAffinityTerm.getTopologyKey(), is(rackKey));
        assertThat(kafkaPodAffinityTerm.getLabelSelector().getMatchLabels(), hasEntry("strimzi.io/cluster", CLUSTER_NAME));
        assertThat(kafkaPodAffinityTerm.getLabelSelector().getMatchLabels(), hasEntry("strimzi.io/name", KafkaResources.kafkaStatefulSetName(CLUSTER_NAME)));

        String rackId = cmdKubeClient().execInPod(KafkaResources.kafkaPodName(CLUSTER_NAME, 0), "/bin/bash", "-c", "cat /opt/kafka/init/rack.id").out();
        assertThat(rackId.trim(), is("zone"));

        String brokerRack = cmdKubeClient().execInPod(KafkaResources.kafkaPodName(CLUSTER_NAME, 0), "/bin/bash", "-c", "cat /tmp/strimzi.properties | grep broker.rack").out();
        assertThat(brokerRack.contains("broker.rack=zone"), is(true));

        Future<Integer> producer = externalBasicKafkaClient.sendMessages(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, MESSAGE_COUNT);
        Future<Integer> consumer = externalBasicKafkaClient.receiveMessages(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, MESSAGE_COUNT);

        assertThat(producer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
        assertThat(consumer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
    }


    @Test
    @Tag(LOADBALANCER_SUPPORTED)
    void testLoadBalancerIpOverride() throws Exception {
        String bootstrapOverrideIP = "10.0.0.1";
        String brokerOverrideIP = "10.0.0.2";

        LoadBalancerListenerBootstrapOverride bootstrapOverride = new LoadBalancerListenerBootstrapOverrideBuilder()
                .withLoadBalancerIP(bootstrapOverrideIP)
                .build();

        LoadBalancerListenerBrokerOverride brokerOverride0 = new LoadBalancerListenerBrokerOverrideBuilder()
                .withBroker(0)
                .withLoadBalancerIP(brokerOverrideIP)
                .build();

        KafkaResource.kafkaEphemeral(CLUSTER_NAME, 3, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalLoadBalancer()
                            .withTls(false)
                        .withNewOverrides()
                            .withBootstrap(bootstrapOverride)
                            .withBrokers(brokerOverride0)
                        .endOverrides()
                        .endKafkaListenerExternalLoadBalancer()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();

        assertThat("Kafka External bootstrap doesn't contain correct loadBalancer address", kubeClient().getService(KafkaResources.externalBootstrapServiceName(CLUSTER_NAME)).getSpec().getLoadBalancerIP(), is(bootstrapOverrideIP));
        assertThat("Kafka Broker-0 service doesn't contain correct loadBalancer address", kubeClient().getService(KafkaResources.brokerSpecificService(CLUSTER_NAME, 0)).getSpec().getLoadBalancerIP(), is(brokerOverrideIP));

        Future<Integer> producer = externalBasicKafkaClient.sendMessages(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, MESSAGE_COUNT);
        Future<Integer> consumer = externalBasicKafkaClient.receiveMessages(TOPIC_NAME, NAMESPACE, CLUSTER_NAME, MESSAGE_COUNT);

        assertThat(producer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
        assertThat(consumer.get(2, TimeUnit.MINUTES), is(MESSAGE_COUNT));
    }

    @Test
    @Tag(REGRESSION)
    void testDeployUnsupportedKafka() {
        String nonExistingVersion = "6.6.6";
        String nonExistingVersionMessage = "Version " + nonExistingVersion + " is not supported.*";

        KafkaResource.kafkaWithoutWait(KafkaResource.defaultKafka(CLUSTER_NAME, 1, 1)
                .editSpec()
                    .editKafka()
                        .withVersion(nonExistingVersion)
                    .endKafka()
                .endSpec().build());

        LOGGER.info("Kafka with version {} deployed.", nonExistingVersion);

        KafkaUtils.waitUntilKafkaCRIsNotReady(CLUSTER_NAME);
        KafkaUtils.waitUntilKafkaStatusConditionContainsMessage(CLUSTER_NAME, NAMESPACE, nonExistingVersionMessage);
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        prepareEnvForOperator(NAMESPACE);

        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        KubernetesResource.clusterOperator(NAMESPACE).done();
    }
}

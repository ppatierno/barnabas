/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class K8sImplTest {

    private static Vertx vertx;

    @BeforeAll
    public static void initVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void closeVertx() {
        vertx.close();
    }


    @Test
    public void testList(VertxTestContext context) {
        Checkpoint async = context.checkpoint();

        KubernetesClient mockClient = mock(KubernetesClient.class);
        MixedOperation<KafkaTopic, KafkaTopicList, TopicOperator.DeleteKafkaTopic, Resource<KafkaTopic, TopicOperator.DeleteKafkaTopic>> mockResources = mock(MixedOperation.class);
        when(mockClient.customResources(any(CustomResourceDefinition.class), any(Class.class), any(Class.class), any(Class.class))).thenReturn(mockResources);
        when(mockResources.withLabels(any())).thenReturn(mockResources);
        when(mockResources.inNamespace(any())).thenReturn(mockResources);
        when(mockResources.list()).thenAnswer(invocation -> {
            KafkaTopicList ktl = new KafkaTopicList();
            ktl.setItems(Collections.singletonList(new KafkaTopicBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName("unrelated")
                    .withLabels(Collections.singletonMap("foo", "bar")).build())
                .build()));
            return ktl;
        });

        K8sImpl k8s = new K8sImpl(vertx, mockClient, new Labels("foo", "bar"), "default");

        k8s.listResources().setHandler(ar -> {
            if (ar.failed()) {
                ar.cause().printStackTrace();
            }
            List<KafkaTopic> list = ar.result();
            context.verify(() -> assertThat(list.isEmpty(), is(false)));
            async.flag();
        });
    }
}

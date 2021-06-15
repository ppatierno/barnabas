/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.strimzi.operator.common.Reconciliation;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaAvailabilityTest {


    static class KSB {
        private Throwable listTopicsResult;
        private final Map<String, Throwable> describeTopicsResult = new HashMap<>(1);
        private final Map<ConfigResource, Throwable> describeConfigsResult = new HashMap<>(1);

        class TSB {
            class PSB {
                private final Integer id;
                private int[] isr = new int[0];
                private Integer leader;
                private int[] replicaOn = new int[0];

                public PSB(Integer p) {
                    this.id = p;
                }
                PSB replicaOn(int... broker) {
                    addBroker(broker);
                    this.replicaOn = broker;
                    return this;
                }

                PSB leader(int broker) {
                    addBroker(broker);
                    this.leader = broker;
                    return this;
                }

                PSB isr(int... broker) {
                    addBroker(broker);
                    this.isr = broker;
                    return this;
                }
                TSB endPartition() {
                    if (this.leader != null) {
                        if (IntStream.of(this.replicaOn).noneMatch(x -> x == this.leader)) {
                            throw new RuntimeException("Leader must be one of the replicas");
                        }
                        if (IntStream.of(this.isr).noneMatch(x -> x == this.leader)) {
                            throw new RuntimeException("ISR must include the leader");
                        }
                    }
                    if (!IntStream.of(this.isr).allMatch(x -> IntStream.of(this.replicaOn).anyMatch(y -> x == y))) {
                        throw new RuntimeException("ISR must be a subset of the replicas");
                    }
                    return TSB.this;
                }
            }
            private final String name;
            private final boolean internal;
            private final Map<String, String> configs = new HashMap<>();
            private final Map<Integer, PSB> partitions = new HashMap<>();

            public TSB(String name, boolean internal) {
                this.name = name;
                this.internal = internal;
            }

            TSB addToConfig(String value) {
                configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, value);
                return this;
            }
            PSB addNewPartition() {
                return partitions.computeIfAbsent(0, PSB::new);
            }


            KSB endTopic() {
                return KSB.this;
            }
        }

        class BSB {

            public BSB(int id) {
                KSB.this.nodes.put(id, new Node(id, "localhost", 1234 + id));
            }

        }

        private final Map<String, TSB> topics = new HashMap<>();
        private final Map<Integer, BSB> brokers = new HashMap<>();
        private final Map<Integer, Node> nodes = new HashMap<>();

        TSB addNewTopic(String name) {
            return topics.computeIfAbsent(name, n -> new TSB(n, false));
        }

        KSB addBroker(int... ids) {
            for (int id : ids) {
                brokers.computeIfAbsent(id, BSB::new);
            }
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static <T> KafkaFuture<T> failedFuture(Throwable t) {
            KafkaFutureImpl kafkaFuture = new KafkaFutureImpl();
            kafkaFuture.completeExceptionally(t);
            return kafkaFuture;
        }

        ListTopicsResult mockListTopics() {
            ListTopicsResult ltr = mock(ListTopicsResult.class);
            when(ltr.names()).thenAnswer(invocation -> listTopicsResult != null ? failedFuture(listTopicsResult) : KafkaFuture.completedFuture(new HashSet<>(topics.keySet())));
            when(ltr.listings()).thenThrow(notImplemented());
            when(ltr.namesToListings()).thenThrow(notImplemented());
            return ltr;
        }

        KSB listTopicsResult(Throwable t) {
            listTopicsResult = t;
            return this;
        }

        KSB describeTopicsResult(Throwable t) {
            describeTopicsResult.put("A", t);
            return this;
        }

        KSB describeConfigsResult(ConfigResource config, Throwable t) {
            describeConfigsResult.put(config, t);
            return this;
        }

        private Throwable notImplemented() {
            //unsupportedOperationException.printStackTrace();
            return new UnsupportedOperationException("Not implemented by " + KSB.class.getName());
        }

        void mockDescribeTopics(Admin mockAc) {
            when(mockAc.describeTopics(any())).thenAnswer(invocation -> {
                DescribeTopicsResult dtr = mock(DescribeTopicsResult.class);
                Collection<String> topicNames = invocation.getArgument(0);
                Throwable throwable = null;
                for (String topicName : topicNames) {
                    throwable = describeTopicsResult.get(topicName);
                    if (throwable != null) {
                        break;
                    }
                }
                if (throwable != null) {
                    when(dtr.all()).thenReturn(failedFuture(throwable));
                } else {
                    Map<String, TopicDescription> tds = topics.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey,
                        e -> {
                            TSB tsb = e.getValue();
                            return new TopicDescription(tsb.name, tsb.internal,
                                    tsb.partitions.values().stream().map(psb -> new TopicPartitionInfo(psb.id,
                                            psb.leader != null ? node(psb.leader) : Node.noNode(),
                                            Arrays.stream(psb.replicaOn).boxed().map(this::node).collect(Collectors.toList()),
                                            Arrays.stream(psb.isr).boxed().map(this::node).collect(Collectors.toList()))).collect(Collectors.toList()));
                        }
                    ));
                    when(dtr.all()).thenReturn(KafkaFuture.completedFuture(tds));
                    when(dtr.values()).thenThrow(notImplemented());
                }
                return dtr;
            });
        }

        private Node node(int id) {
            return nodes.computeIfAbsent(id, x -> {
                throw new RuntimeException("Unknown node " + id);
            });
        }

        void mockDescribeConfigs(Admin mockAc) {
            when(mockAc.describeConfigs(any())).thenAnswer(invocation -> {
                Collection<ConfigResource> argument = invocation.getArgument(0);
                DescribeConfigsResult dcr = mock(DescribeConfigsResult.class);
                Throwable throwable = null;
                for (ConfigResource configResource : argument) {
                    throwable = describeConfigsResult.get(configResource);
                    if (throwable != null) {
                        break;
                    }
                }
                when(dcr.values()).thenThrow(notImplemented());
                if (throwable != null) {
                    when(dcr.all()).thenReturn(failedFuture(throwable));
                } else {
                    Map<ConfigResource, Config> result = new HashMap<>();
                    for (ConfigResource cr : argument) {
                        List<ConfigEntry> entries = new ArrayList<>();
                        for (Map.Entry<String, String> e : topics.get(cr.name()).configs.entrySet()) {
                            ConfigEntry ce = new ConfigEntry(e.getKey(), e.getValue());
                            entries.add(ce);
                        }
                        result.put(cr, new Config(entries));
                    }
                    when(dcr.all()).thenReturn(KafkaFuture.completedFuture(result));
                }
                return dcr;
            });
        }

        Admin ac() {
            Admin ac = mock(AdminClient.class);

            ListTopicsResult ltr = mockListTopics();
            when(ac.listTopics(any())).thenReturn(ltr);

            mockDescribeTopics(ac);

            mockDescribeConfigs(ac);

            return ac;
        }
    }

    @Test
    public void testBelowMinIsr(VertxTestContext context) {
        KSB ksb = new KSB()
            .addNewTopic("A")
                .addToConfig("2")
                .addNewPartition()
                    .replicaOn(0, 1, 3)
                    .leader(0)
                    .isr(0, 1)
                .endPartition()
            .endTopic()
            .addNewTopic("B")
                .addToConfig("2")
                .addNewPartition()
                    .replicaOn(0, 1, 3)
                    .leader(1)
                    .isr(1)
                .endPartition()
            .endTopic()

            .addBroker(4);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                if (brokerId == 4) {
                    assertTrue(canRoll,
                            "broker " + brokerId + " should be rollable, having no partitions");
                } else {
                    assertFalse(canRoll,
                            "broker " + brokerId + " should not be rollable, being minisr = 2 and it's only replicated on two brokers");
                }
                context.completeNow();
            })));
        }
    }

    @Test
    public void testAtMinIsr(VertxTestContext context) {
        KSB ksb = new KSB()
            .addNewTopic("A")
                .addToConfig("2")
                .addNewPartition()
                    .replicaOn(0, 1)
                    .leader(0)
                    .isr(0, 1)
                .endPartition()
            .endTopic()
            .addNewTopic("B")
                .addToConfig("2")
                .addNewPartition()
                    .replicaOn(0, 1)
                    .leader(1)
                    .isr(0, 1)
                .endPartition()
            .endTopic()

            .addBroker(2);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                if (brokerId == 2) {
                    assertTrue(canRoll,
                            "broker " + brokerId + " should be rollable, having no partitions");
                } else {
                    assertTrue(canRoll,
                            "broker " + brokerId + " should be rollable, because although rolling it will impact availability minisr=|replicas|");
                }
                context.completeNow();
            })));
        }
    }

    @Test
    public void testAboveMinIsr(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(1)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                assertTrue(canRoll,
                        "broker " + brokerId + " should be rollable, being minisr = 1 and having two brokers in its isr");
                context.completeNow();
            })));
        }
    }

    @Test
    public void testMinIsrEqualsReplicas(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("3")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                assertTrue(canRoll,
                        "broker " + brokerId + " should be rollable, being minisr = 3, but only 3 replicas");

                context.completeNow();
            })));
        }
    }

    @Test
    public void testMinIsrEqualsReplicasWithOfflineReplicas(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                .addToConfig("3")
                .addNewPartition()
                .replicaOn(0, 1, 2)
                .leader(0)
                .isr(0, 1)
                .endPartition()
                .endTopic()

                .addBroker(3);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                assertTrue(canRoll,
                        "broker " + brokerId + " should be rollable, being minisr = 3, but only 3 replicas");

                context.completeNow();
            })));
        }
    }

    @Test
    public void testMinIsrMoreThanReplicas(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("2")
                    .addNewPartition()
                        .replicaOn(0)
                        .leader(0)
                        .isr(0)
                    .endPartition()
                .endTopic()
                .addBroker(3);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                assertTrue(canRoll,
                        "broker " + brokerId + " should be rollable, being minisr = 2, but only 1 replicas");

                context.completeNow();
            })));
        }
    }

    @Test
    public void testNoLeader(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        //.leader(0)
                        .isr(1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        //.leader(1)
                        .isr(0)
                    .endPartition()
                .endTopic()

                .addBroker(3);

        KafkaAvailability kafkaSorted = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaSorted.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                if (brokerId == 0) {
                    assertFalse(canRoll,
                            "broker " + brokerId + " should not be rollable, because B/0 would be below min isr");
                } else {
                    assertTrue(canRoll,
                            "broker " + brokerId + " should be rollable, being minisr = 1 and having two brokers in its isr");
                }
                context.completeNow();
            })));
        }
    }

    @Test
    public void testNoMinIsr(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(1)
                        .isr(1, 0, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3);

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.verify(() -> {
                assertTrue(canRoll,
                        "broker " + brokerId + " should be rollable, being minisr = 1 and having two brokers in its isr");
                context.completeNow();
            })));
        }
    }

    // TODO when AC throws various exceptions (e.g. UnknownTopicOrPartitionException)
    @Test
    public void testCanRollThrowsTimeoutExceptionWhenTopicsListThrowsException(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(1)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3)
                .listTopicsResult(new TimeoutException());

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.failing(e -> context.verify(() -> {
                assertThat(e, instanceOf(TimeoutException.class));
                context.completeNow();
            })));
        }
    }

    @Test
    public void testCanRollThrowsExceptionWhenTopicDescribeThrows(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(1)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3)
                .describeTopicsResult(new UnknownTopicOrPartitionException());

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            kafkaAvailability.canRoll(brokerId).onComplete(context.failing(e -> context.verify(() -> {
                assertThat(e, instanceOf(UnknownTopicOrPartitionException.class));
                context.completeNow();
            })));
        }
    }

    @Test
    public void testCanRollThrowsExceptionWhenDescribeConfigsThrows(VertxTestContext context) {
        KSB ksb = new KSB()
                .addNewTopic("A")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(0)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()
                .addNewTopic("B")
                    .addToConfig("1")
                    .addNewPartition()
                        .replicaOn(0, 1, 2)
                        .leader(1)
                        .isr(0, 1, 2)
                    .endPartition()
                .endTopic()

                .addBroker(3)
                .describeConfigsResult(new ConfigResource(ConfigResource.Type.TOPIC, "A"), new UnknownTopicOrPartitionException());

        KafkaAvailability kafkaAvailability = new KafkaAvailability(new Reconciliation("dummy", "kind", "namespace", "A"), ksb.ac());

        for (Integer brokerId : ksb.brokers.keySet()) {
            if (brokerId <= 2) {
                kafkaAvailability.canRoll(brokerId).onComplete(context.failing(e -> context.verify(() -> {
                    assertThat(e, instanceOf(UnknownTopicOrPartitionException.class));
                    context.completeNow();
                })));
            } else {
                kafkaAvailability.canRoll(brokerId).onComplete(context.succeeding(canRoll -> context.completeNow()));
            }
        }
    }
}

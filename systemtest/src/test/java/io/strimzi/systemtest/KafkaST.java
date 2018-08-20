/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Job;
import io.fabric8.kubernetes.api.model.JobBuilder;
import io.fabric8.kubernetes.api.model.JobStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.ZookeeperClusterSpec;
import io.strimzi.test.ClusterOperator;
import io.strimzi.test.JUnitGroup;
import io.strimzi.test.KafkaFromClasspathYaml;
import io.strimzi.test.Namespace;
import io.strimzi.test.OpenShiftOnly;
import io.strimzi.test.Resources;
import io.strimzi.test.StrimziRunner;
import io.strimzi.test.TestUtils;
import io.strimzi.test.TimeoutException;
import io.strimzi.test.Topic;
import io.strimzi.test.k8s.Oc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.systemtest.k8s.Events.Created;
import static io.strimzi.systemtest.k8s.Events.Failed;
import static io.strimzi.systemtest.k8s.Events.FailedSync;
import static io.strimzi.systemtest.k8s.Events.FailedValidation;
import static io.strimzi.systemtest.k8s.Events.Killing;
import static io.strimzi.systemtest.k8s.Events.Pulled;
import static io.strimzi.systemtest.k8s.Events.Scheduled;
import static io.strimzi.systemtest.k8s.Events.Started;
import static io.strimzi.systemtest.k8s.Events.SuccessfulDelete;
import static io.strimzi.systemtest.k8s.Events.Unhealthy;
import static io.strimzi.systemtest.matchers.Matchers.hasAllOfReasons;
import static io.strimzi.systemtest.matchers.Matchers.hasNoneOfReasons;
import static io.strimzi.test.StrimziRunner.TOPIC_CM;
import static io.strimzi.test.TestUtils.fromYamlString;
import static io.strimzi.test.TestUtils.indent;
import static io.strimzi.test.TestUtils.map;
import static io.strimzi.test.TestUtils.waitFor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.valid4j.matchers.jsonpath.JsonPathMatchers.hasJsonPath;

@RunWith(StrimziRunner.class)
@Namespace(KafkaST.NAMESPACE)
@ClusterOperator
public class KafkaST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(KafkaST.class);

    public static final String NAMESPACE = "kafka-cluster-test";
    private static final String TOPIC_NAME = "test-topic";

    static KubernetesClient client = new DefaultKubernetesClient();

    @Test
    @JUnitGroup(name = "regression")
    @OpenShiftOnly
    @Resources(value = "../examples/templates/cluster-operator", asAdmin = true)
    public void testDeployKafkaClusterViaTemplate() {
        Oc oc = (Oc) this.kubeClient;
        String clusterName = "openshift-my-cluster";
        oc.newApp("strimzi-ephemeral", map("CLUSTER_NAME", clusterName));
        oc.waitForStatefulSet(zookeeperClusterName(clusterName), 3);
        oc.waitForStatefulSet(kafkaClusterName(clusterName), 3);

        //Testing docker images
        testDockerImagesForKafkaCluster(clusterName, 3, 3, false);

        oc.deleteByName("Kafka", clusterName);
        oc.waitForResourceDeletion("statefulset", kafkaClusterName(clusterName));
        oc.waitForResourceDeletion("statefulset", zookeeperClusterName(clusterName));
    }

    @Test
    @JUnitGroup(name = "acceptance")
    @KafkaFromClasspathYaml()
    public void testKafkaAndZookeeperScaleUpScaleDown() {
        testDockerImagesForKafkaCluster(CLUSTER_NAME, 3, 1, false);
        // kafka cluster already deployed via annotation
        LOGGER.info("Running kafkaScaleUpScaleDown {}", CLUSTER_NAME);
        //kubeClient.waitForStatefulSet(kafkaStatefulSetName(clusterName), 3);

        final int initialReplicas = client.apps().statefulSets().inNamespace(kubeClient.namespace()).withName(kafkaClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(3, initialReplicas);
        // scale up
        final int scaleTo = initialReplicas + 1;
        final int newPodId = initialReplicas;
        final int newBrokerId = newPodId;
        final String newPodName = kafkaPodName(CLUSTER_NAME,  newPodId);
        final String firstPodName = kafkaPodName(CLUSTER_NAME,  0);
        LOGGER.info("Scaling up to {}", scaleTo);
        replaceKafkaResource(CLUSTER_NAME, k -> {
            k.getSpec().getKafka().setReplicas(initialReplicas + 1);
        });
        kubeClient.waitForStatefulSet(kafkaClusterName(CLUSTER_NAME), initialReplicas + 1);

        // Test that the new broker has joined the kafka cluster by checking it knows about all the other broker's API versions
        // (execute bash because we want the env vars expanded in the pod)
        String versions = getBrokerApiVersions(newPodName);
        for (int brokerId = 0; brokerId < scaleTo; brokerId++) {
            assertTrue(versions, versions.indexOf("(id: " + brokerId + " rack: ") >= 0);
        }

        //Test that the new pod does not have errors or failures in events
        List<Event> events = getEvents("Pod", newPodName);
        assertThat(events, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(events, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(stopwatch.runtime(SECONDS));

        // scale down
        LOGGER.info("Scaling down");
        //client.apps().statefulSets().inNamespace(NAMESPACE).withName(kafkaStatefulSetName(CLUSTER_NAME)).scale(initialReplicas, true);
        replaceKafkaResource(CLUSTER_NAME, k -> {
            k.getSpec().getKafka().setReplicas(initialReplicas);
        });
        kubeClient.waitForStatefulSet(kafkaClusterName(CLUSTER_NAME), initialReplicas);

        final int finalReplicas = client.apps().statefulSets().inNamespace(kubeClient.namespace()).withName(kafkaClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(initialReplicas, finalReplicas);
        versions = getBrokerApiVersions(firstPodName);

        assertTrue("Expect the added broker, " + newBrokerId + ",  to no longer be present in output of kafka-broker-api-versions.sh",
                versions.indexOf("(id: " + newBrokerId + " rack: ") == -1);

        //Test that the new broker has event 'Killing'
        assertThat(getEvents("Pod", newPodName), hasAllOfReasons(Killing));
        //Test that stateful set has event 'SuccessfulDelete'
        assertThat(getEvents("StatefulSet", kafkaClusterName(CLUSTER_NAME)), hasAllOfReasons(SuccessfulDelete));
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(stopwatch.runtime(SECONDS));
    }

    @Test
    @JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml()
    public void testZookeeperScaleUpScaleDown() {
        // kafka cluster already deployed via annotation
        LOGGER.info("Running zookeeperScaleUpScaleDown with cluster {}", CLUSTER_NAME);
        //kubeClient.waitForStatefulSet(zookeeperStatefulSetName(CLUSTER_NAME), 1);
        KubernetesClient client = new DefaultKubernetesClient();
        final int initialZkReplicas = client.apps().statefulSets().inNamespace(kubeClient.namespace()).withName(zookeeperClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(1, initialZkReplicas);

        // scale up
        final int scaleZkTo = initialZkReplicas + 2;
        final int[] newPodIds = {initialZkReplicas, initialZkReplicas + 1};
        final String[] newZkPodName = {
                zookeeperPodName(CLUSTER_NAME,  newPodIds[0]),
                zookeeperPodName(CLUSTER_NAME,  newPodIds[1])
        };
        final String firstZkPodName = zookeeperPodName(CLUSTER_NAME,  0);
        LOGGER.info("Scaling up to {}", scaleZkTo);
        replaceKafkaResource(CLUSTER_NAME, k -> {
            k.getSpec().getZookeeper().setReplicas(scaleZkTo);
        });
        kubeClient.waitForPod(newZkPodName[0]);
        kubeClient.waitForPod(newZkPodName[1]);

        // check the new node is either in leader or follower state
        waitForZkMntr(Pattern.compile("zk_server_state\\s+(leader|follower)"), 0, 1, 2);

        //Test that first pod does not have errors or failures in events
        List<Event> eventsForFirstPod = getEvents("Pod", newZkPodName[0]);
        assertThat(eventsForFirstPod, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(eventsForFirstPod, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));

        //Test that second pod does not have errors or failures in events
        List<Event> eventsForSecondPod = getEvents("Pod", newZkPodName[1]);
        assertThat(eventsForSecondPod, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(eventsForSecondPod, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));

        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(stopwatch.runtime(SECONDS));

        // scale down
        LOGGER.info("Scaling down");
        replaceKafkaResource(CLUSTER_NAME, k -> {
            k.getSpec().getZookeeper().setReplicas(1);
        });
        kubeClient.waitForResourceDeletion("po", zookeeperPodName(CLUSTER_NAME,  1));
        // Wait for the one remaining node to enter standalone mode
        waitForZkMntr(Pattern.compile("zk_server_state\\s+standalone"), 0);

        //Test that the second pod has event 'Killing'
        assertThat(getEvents("Pod", newZkPodName[1]), hasAllOfReasons(Killing));
        //Test that stateful set has event 'SuccessfulDelete'
        assertThat(getEvents("StatefulSet", zookeeperClusterName(CLUSTER_NAME)), hasAllOfReasons(SuccessfulDelete));
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(stopwatch.runtime(SECONDS));
    }

    @Test
    @JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml()
    public void testCustomAndUpdatedValues() {
        String clusterName = "my-cluster";
        int expectedZKPods = 2;
        int expectedKafkaPods = 2;
        List<Date> zkPodStartTime = new ArrayList<>();
        for (int i = 0; i < expectedZKPods; i++) {
            zkPodStartTime.add(kubeClient.getResourceCreateTimestamp("pod", zookeeperPodName(clusterName, i)));
        }
        List<Date> kafkaPodStartTime = new ArrayList<>();
        for (int i = 0; i < expectedKafkaPods; i++) {
            kafkaPodStartTime.add(kubeClient.getResourceCreateTimestamp("pod", kafkaPodName(clusterName, i)));
        }

        LOGGER.info("Verify values before update");
        for (int i = 0; i < expectedKafkaPods; i++) {
            String kafkaPodJson = kubeClient.getResourceAsJson("pod", kafkaPodName(clusterName, i));
            assertEquals("transaction.state.log.replication.factor=1\\ndefault.replication.factor=1\\noffsets.topic.replication.factor=1\\n".replaceAll("\\p{P}", ""), getValueFromJson(kafkaPodJson,
                    globalVariableJsonPathBuilder("KAFKA_CONFIGURATION")));
            assertThat(kafkaPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.initialDelaySeconds", hasItem(30)));
            assertThat(kafkaPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.timeoutSeconds", hasItem(10)));
        }
        LOGGER.info("Testing Zookeepers");
        for (int i = 0; i < expectedZKPods; i++) {
            String zkPodJson = kubeClient.getResourceAsJson("pod", zookeeperPodName(clusterName, i));
            assertEquals("timeTick=2000\\nautopurge.purgeInterval=1\\nsyncLimit=2\\ninitLimit=5\\n".replaceAll("\\p{P}", ""), getValueFromJson(zkPodJson,
                    globalVariableJsonPathBuilder("ZOOKEEPER_CONFIGURATION")));
            assertThat(zkPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.initialDelaySeconds", hasItem(30)));
            assertThat(zkPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.timeoutSeconds", hasItem(10)));
        }

        replaceKafkaResource(clusterName, k -> {
            KafkaClusterSpec kafkaClusterSpec = k.getSpec().getKafka();
            kafkaClusterSpec.getLivenessProbe().setInitialDelaySeconds(31);
            kafkaClusterSpec.getReadinessProbe().setInitialDelaySeconds(31);
            kafkaClusterSpec.getLivenessProbe().setTimeoutSeconds(11);
            kafkaClusterSpec.getReadinessProbe().setTimeoutSeconds(11);
            kafkaClusterSpec.setConfig(TestUtils.fromJson("{\"default.replication.factor\": 2,\"offsets.topic.replication.factor\": 2,\"transaction.state.log.replication.factor\": 2}", Map.class));
            ZookeeperClusterSpec zookeeperClusterSpec = k.getSpec().getZookeeper();
            zookeeperClusterSpec.getLivenessProbe().setInitialDelaySeconds(31);
            zookeeperClusterSpec.getReadinessProbe().setInitialDelaySeconds(31);
            zookeeperClusterSpec.getLivenessProbe().setTimeoutSeconds(11);
            zookeeperClusterSpec.getReadinessProbe().setTimeoutSeconds(11);
            zookeeperClusterSpec.setConfig(TestUtils.fromJson("{\"timeTick\": 2100, \"initLimit\": 6, \"syncLimit\": 3}", Map.class));
        });

        for (int i = 0; i < expectedZKPods; i++) {
            kubeClient.waitForResourceUpdate("pod", zookeeperPodName(clusterName, i), zkPodStartTime.get(i));
            kubeClient.waitForPod(zookeeperPodName(clusterName,  i));
        }
        for (int i = 0; i < expectedKafkaPods; i++) {
            kubeClient.waitForResourceUpdate("pod", kafkaPodName(clusterName, i), kafkaPodStartTime.get(i));
            kubeClient.waitForPod(kafkaPodName(clusterName,  i));
        }

        LOGGER.info("Verify values after update");
        for (int i = 0; i < expectedKafkaPods; i++) {
            String kafkaPodJson = kubeClient.getResourceAsJson("pod", kafkaPodName(clusterName, i));
            assertEquals("transaction.state.log.replication.factor=2\\ndefault.replication.factor=2\\noffsets.topic.replication.factor=2\\n".replaceAll("\\p{P}", ""), getValueFromJson(kafkaPodJson,
                    globalVariableJsonPathBuilder("KAFKA_CONFIGURATION")));

            assertThat(kafkaPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.initialDelaySeconds", hasItem(31)));
            assertThat(kafkaPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.timeoutSeconds", hasItem(11)));
        }
        LOGGER.info("Testing Zookeepers");
        for (int i = 0; i < expectedZKPods; i++) {
            String zkPodJson = kubeClient.getResourceAsJson("pod", zookeeperPodName(clusterName, i));
            assertEquals("timeTick=2100\\nautopurge.purgeInterval=1\\nsyncLimit=3\\ninitLimit=6\\n".replaceAll("\\p{P}", ""), getValueFromJson(zkPodJson,
                    globalVariableJsonPathBuilder("ZOOKEEPER_CONFIGURATION")));
            assertThat(zkPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.initialDelaySeconds", hasItem(31)));
            assertThat(zkPodJson, hasJsonPath("$.spec.containers[*].livenessProbe.timeoutSeconds", hasItem(11)));
        }
    }

    @Test
    @JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml()
    @Topic(name = TOPIC_NAME, clusterName = "my-cluster")
    public void testSendMessages() {
        int messagesCount = 20;
        sendMessages(kafkaPodName(CLUSTER_NAME, 1), CLUSTER_NAME, TOPIC_NAME, messagesCount);
        String consumedMessages = consumeMessages(CLUSTER_NAME, TOPIC_NAME, 1, 30, 2);

        assertThat(consumedMessages, hasJsonPath("$[*].count", hasItem(messagesCount)));
        assertThat(consumedMessages, hasJsonPath("$[*].partitions[*].topic", hasItem(TOPIC_NAME)));

    }

    /**
     * Test sending messages over plain transport, without auth
     */
    @Test
    @JUnitGroup(name = "acceptance")
    //@KafkaFromClasspathYaml()
    public void testSendMessagesPlainAnonymous() throws InterruptedException {
        String name = "send-messages-plain-anon";
        int messagesCount = 20;

        resources().kafkaEphemeral(CLUSTER_NAME, 3).done();
        resources().topic(CLUSTER_NAME, TOPIC_NAME).done();

        // Create ping job
        Job job = waitForJobSuccess(pingJob(name, TOPIC_NAME, messagesCount, null, false));

        // Now get the pod logs (which will be both producer and consumer logs)
        checkPings(messagesCount, job);
    }

    /**
     * Test sending messages over tls transport using mutual tls auth
     */
    @Test
    @JUnitGroup(name = "acceptance")
    //@KafkaFromClasspathYaml()
    public void testSendMessagesTlsAuthenticated() {
        String kafkaUser = "my-user";
        String name = "send-messages-tls-auth";
        int messagesCount = 20;

        // Use a Kafka with plain listener disabled
        resources().kafka(resources().defaultKafka(CLUSTER_NAME, 3)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .withNewTls().endTls()
                        .endListeners()
                    .endKafka()
                .endSpec().build()).done();
        resources().topic(CLUSTER_NAME, TOPIC_NAME).done();
        resources().tlsUser(kafkaUser).done();

        // Create ping job
        Job job = waitForJobSuccess(pingJob(name, TOPIC_NAME, messagesCount, kafkaUser, true));

        // Now check the pod logs the messages were produced and consumed
        checkPings(messagesCount, job);
    }

    /** Get the log of the pod with the given name */
    private String podLog(String podName) {
        return namespacedClient().pods().withName(podName).getLog();
    }

    /** Get the name of the pod for a job */
    private String jobPodName(Job job) {
        Map<String, String> labels = job.getSpec().getTemplate().getMetadata().getLabels();
        List<Pod> pods = namespacedClient().pods().withLabels(labels).list().getItems();
        if (pods.size() != 1) {
            fail("There are " + pods.size() +  " pods with labels " + labels);
        }
        return pods.get(0).getMetadata().getName();
    }

    /**
     * Greps logs from a pod which ran kafka-verifiable-producer.sh and
     * kafka-verifiable-consumer.sh
     */
    private void checkPings(int messagesCount, Job job) {
        String podName = jobPodName(job);
        String log = podLog(podName);
        Pattern p = Pattern.compile("^\\{.*\\}$", Pattern.MULTILINE);
        Matcher m = p.matcher(log);
        boolean producerSuccess = false;
        boolean consumerSuccess = false;
        while (m.find()) {
            String json = m.group();
            String name2 = getValueFromJson(json, "$.name");
            if ("tooldata".equals(name2)) {
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.sent"));
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.acked"));
                producerSuccess = true;
            } else if ("recordsconsumed".equals(name2)) {
                assertEquals(String.valueOf(messagesCount), getValueFromJson(json, "$.count"));
                consumerSuccess = true;
            }
        }
        if (!producerSuccess || !consumerSuccess) {
            LOGGER.info("log from pod {}:\n----\n{}\n----", podName, indent(log));
        }
        assertTrue("The producer didn't send any messages (no tool_data message)", producerSuccess);
        assertTrue("The consumer didn't consume any messages (no records_consumed message)", consumerSuccess);
    }

    /**
     * Waits for a job to complete successfully, {@link org.junit.Assert#fail()}ing
     * if it completes with any failed pods.
     * @throws TimeoutException if the job doesn't complete quickly enough.
     */
    private Job waitForJobSuccess(Job job) {
        // Wait for the job to succeed
        try {
            LOGGER.info("Waiting for Job completion: {}", job);
            waitFor("Job completion", 10000, 150000, () -> {
                Job jobs = namespacedClient().extensions().jobs().withName(job.getMetadata().getName()).get();
                JobStatus status;
                if (jobs == null || (status = jobs.getStatus()) == null) {
                    LOGGER.info("Poll job is null");
                    return false;
                } else {
                    if (status.getFailed() != null && status.getFailed() > 0) {
                        LOGGER.info("Poll job failed");
                        fail();
                    } else if (status.getSucceeded() != null && status.getSucceeded() == 1) {
                        LOGGER.info("Poll job succeeded");
                        return true;
                    } else if (status.getActive() > 0) {
                        LOGGER.info("Poll job has active");
                        return false;
                    }
                }
                throw new RuntimeException("Unexpected state");
            });
            return job;
        } catch (TimeoutException e) {
            LOGGER.info("Original Job: {}", job);
            try {
                LOGGER.info("Job: {}", namespacedClient().extensions().jobs().withName(job.getMetadata().getName()).get());
            } catch (Exception | AssertionError t) {
                LOGGER.info("Job not available: {}", t.getMessage());
            }
            try {
                LOGGER.info("Pod: {}", TestUtils.toYamlString(namespacedClient().pods().withName(jobPodName(job)).get()));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Pod not available: {}", t.getMessage());
            }
            try {
                LOGGER.info("Job timeout: Pod logs\n----\n{}\n----", podLog(jobPodName(job)));
            } catch (Exception | AssertionError t) {
                LOGGER.info("Pod logs not available: {}", t.getMessage());
            }
            throw e;
        }
    }

    /**
     * Create a Job which which produce and then consume messages to a given topic.
     * The job will be deleted from the kubernetes cluster at the end of the test.
     * @param name The name of the {@code Job} and also the consumer group id.
     *             The Job's pod will also use this in a {@code job=<name>} selector.
     * @param messagesCount The number of messages to produce & consume
     * @return The job
     */
    private Job pingJob(String name, String topic, int messagesCount, String kafkaUser, boolean tls) {

        String connect = tls ? CLUSTER_NAME + "-kafka-bootstrap:9093" : CLUSTER_NAME + "-kafka-bootstrap:9092";
        ContainerBuilder cb = new ContainerBuilder()
                .withName("ping")
                .withImage(TestUtils.changeOrgAndTag("strimzi/test-client:latest"))
                .addNewEnv().withName("PRODUCER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                        "--topic " + topic + " " +
                        "--max-messages " + messagesCount).endEnv()
                .addNewEnv().withName("CONSUMER_OPTS").withValue(
                        "--broker-list " + connect + " " +
                        "--group-id " + name + "-" + new Random().nextInt() + " " +
                        "--verbose " +
                        "--topic " + topic + " " +
                        "--max-messages " + messagesCount).endEnv()
                .withCommand("/opt/kafka/ping.sh");

        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withRestartPolicy("OnFailure");

        if (tls) {
            String clusterCaSecretName = CLUSTER_NAME + "-cluster-ca-cert";
            String clusterCaSecretVolumeName = "ca-cert";

            String userSecretVolumeName = "tls-cert";
            String userSecretMountPoint = "/opt/kafka/user-secret";
            String caSecretMountPoint = "/opt/kafka/cluster-ca";
            cb.addNewVolumeMount()
                    .withName(userSecretVolumeName)
                    .withMountPath(userSecretMountPoint)
                .endVolumeMount()
                .addNewVolumeMount()
                    .withName(clusterCaSecretVolumeName)
                    .withMountPath(caSecretMountPoint)
                .endVolumeMount()
                .addNewEnv().withName("PRODUCER_CONFIGURATION").withValue("\n" +
                    "security.protocol=SSL\n" +
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                    "ssl.keystore.type=pkcs12").endEnv()
                .addNewEnv().withName("CONSUMER_CONFIGURATION").withValue("\n" +
                    "auto.offset.reset=earliest\n" +
                    "security.protocol=SSL\n" +
                    "ssl.keystore.location=/tmp/keystore.p12\n" +
                    "ssl.truststore.location=/tmp/truststore.p12\n" +
                    "ssl.keystore.type=pkcs12").endEnv()
                .addNewEnv().withName("PRODUCER_TLS").withValue("TRUE").endEnv()
                .addNewEnv().withName("CONSUMER_TLS").withValue("TRUE").endEnv()
                .addNewEnv().withName("KAFKA_USER").withValue(kafkaUser).endEnv()
                .addNewEnv().withName("USER_LOCATION").withValue(userSecretMountPoint).endEnv()
                .addNewEnv().withName("CA_LOCATION").withValue(caSecretMountPoint).endEnv()
                .addNewEnv().withName("TRUSTSTORE_LOCATION").withValue("/tmp/truststore.p12").endEnv()
                .addNewEnv().withName("KEYSTORE_LOCATION").withValue("/tmp/keystore.p12").endEnv();

            podSpecBuilder
                .addNewVolume()
                    .withName(userSecretVolumeName)
                    .withNewSecret()
                        .withSecretName(kafkaUser)
                    .endSecret()
                .endVolume()
                .addNewVolume()
                    .withName(clusterCaSecretVolumeName)
                    .withNewSecret()
                        .withSecretName(clusterCaSecretName)
                    .endSecret()
                .endVolume();
        }

        Job job = resources().deleteLater(namespacedClient().extensions().jobs().create(new JobBuilder()
                .withNewMetadata()
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withName(name)
                            .addToLabels("job", name)
                        .endMetadata()
                        .withSpec(podSpecBuilder.withContainers(cb.build()).build())
                    .endTemplate()
                .endSpec()
                .build()));
        LOGGER.info("Created Job {}", job);
        return job;
    }

    /**
     *
     */
    @KafkaFromClasspathYaml
    @Test
    @JUnitGroup(name = "regression")
    public void testJvmAndResources() {
        assertResources(kubeClient.namespace(), "jvm-resource-cluster-kafka-0",
                "2Gi", "400m", "2Gi", "400m");
        assertExpectedJavaOpts("jvm-resource-cluster-kafka-0",
                "-Xmx1g", "-Xms1G", "-server", "-XX:+UseG1GC");

        assertResources(kubeClient.namespace(), "jvm-resource-cluster-zookeeper-0",
                "1Gi", "300m", "1Gi", "300m");
        assertExpectedJavaOpts("jvm-resource-cluster-zookeeper-0",
                "-Xmx600m", "-Xms300m", "-server", "-XX:+UseG1GC");

        String podName = client.pods().inNamespace(kubeClient.namespace()).list().getItems()
                .stream().filter(p -> p.getMetadata().getName().startsWith("jvm-resource-cluster-entity-operator-"))
                .findFirst().get().getMetadata().getName();

        assertResources(kubeClient.namespace(), podName,
                "500M", "300m", "500M", "300m");
    }

    @Test
    @JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml
    public void testForTopicOperator() throws InterruptedException {
        //Createing topics for testing
        kubeClient.create(TOPIC_CM);
        assertThat(listTopicsUsingPodCLI(CLUSTER_NAME, 0), hasItem("my-topic"));

        createTopicUsingPodCLI(CLUSTER_NAME, 0, "topic-from-cli", 1, 1);
        assertThat(listTopicsUsingPodCLI(CLUSTER_NAME, 0), hasItems("my-topic", "topic-from-cli"));
        assertThat(kubeClient.list("kafkatopic"), hasItems("my-topic", "topic-from-cli", "my-topic"));

        //Updating first topic using pod CLI
        updateTopicPartitionsCountUsingPodCLI(CLUSTER_NAME, 0, "my-topic", 2);
        assertThat(describeTopicUsingPodCLI(CLUSTER_NAME, 0, "my-topic"),
                hasItems("PartitionCount:2"));
        KafkaTopic testTopic = fromYamlString(kubeClient.get("kafkatopic", "my-topic"), KafkaTopic.class);
        assertNotNull(testTopic);
        assertNotNull(testTopic.getSpec());
        assertEquals(Integer.valueOf(2), testTopic.getSpec().getPartitions());

        //Updating second topic via KafkaTopic update
        replaceTopicResource("topic-from-cli", topic -> {
            topic.getSpec().setPartitions(2);
        });
        assertThat(describeTopicUsingPodCLI(CLUSTER_NAME, 0, "topic-from-cli"),
                hasItems("PartitionCount:2"));
        testTopic = fromYamlString(kubeClient.get("kafkatopic", "topic-from-cli"), KafkaTopic.class);
        assertNotNull(testTopic);
        assertNotNull(testTopic.getSpec());
        assertEquals(Integer.valueOf(2), testTopic.getSpec().getPartitions());

        //Deleting first topic by deletion of CM
        kubeClient.deleteByName("kafkatopic", "topic-from-cli");

        //Deleting another topic using pod CLI
        deleteTopicUsingPodCLI(CLUSTER_NAME, 0, "my-topic");
        kubeClient.waitForResourceDeletion("kafkatopic", "my-topic");
        Thread.sleep(10000L);
        List<String> topics = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
        assertThat(topics, not(hasItems("my-topic")));
        assertThat(topics, not(hasItems("topic-from-cli")));
    }

    private void testDockerImagesForKafkaCluster(String clusterName, int kafkaPods, int zkPods, boolean rackAwareEnabled) {
        LOGGER.info("Verifying docker image names");
        //Verifying docker image for cluster-operator

        Map<String, String> imgFromDeplConf = getImagesFromConfig(kubeClient.getResourceAsJson(
                "deployment", "strimzi-cluster-operator"));

        //Verifying docker image for zookeeper pods
        for (int i = 0; i < zkPods; i++) {
            String imgFromPod = getContainerImageNameFromPod(zookeeperPodName(clusterName, i), "zookeeper");
            assertEquals(imgFromDeplConf.get(ZK_IMAGE), imgFromPod);
            imgFromPod = getContainerImageNameFromPod(zookeeperPodName(clusterName, i), "tls-sidecar");
            assertEquals(imgFromDeplConf.get(TLS_SIDECAR_ZOOKEEPER_IMAGE), imgFromPod);
        }

        //Verifying docker image for kafka pods
        for (int i = 0; i < kafkaPods; i++) {
            String imgFromPod = getContainerImageNameFromPod(kafkaPodName(clusterName, i), "kafka");
            assertEquals(imgFromDeplConf.get(KAFKA_IMAGE), imgFromPod);
            imgFromPod = getContainerImageNameFromPod(kafkaPodName(clusterName, i), "tls-sidecar");
            assertEquals(imgFromDeplConf.get(TLS_SIDECAR_KAFKA_IMAGE), imgFromPod);
            if (rackAwareEnabled) {
                String initContainerImage = getInitContainerImageName(kafkaPodName(clusterName, i));
                assertEquals(imgFromDeplConf.get(KAFKA_INIT_IMAGE), initContainerImage);
            }
        }

        //Verifying docker image for entity-operator
        String entityOperatorPodName = kubeClient.listResourcesByLabel("pod",
                "strimzi.io/name=" + clusterName + "-entity-operator").get(0);
        String imgFromPod = getContainerImageNameFromPod(entityOperatorPodName, "entity-topic-operator");
        assertEquals(imgFromDeplConf.get(TO_IMAGE), imgFromPod);
        imgFromPod = getContainerImageNameFromPod(entityOperatorPodName, "entity-user-operator");
        assertEquals(imgFromDeplConf.get(UO_IMAGE), imgFromPod);
        imgFromPod = getContainerImageNameFromPod(entityOperatorPodName, "tls-sidecar");
        assertEquals(imgFromDeplConf.get(TLS_SIDECAR_EO_IMAGE), imgFromPod);

        LOGGER.info("Docker images verified");
    }

    @Test
    @JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml
    public void testRackAware() {
        testDockerImagesForKafkaCluster(CLUSTER_NAME, 1, 1, true);

        String kafkaPodName = kafkaPodName(CLUSTER_NAME, 0);
        kubeClient.waitForPod(kafkaPodName);

        String rackId = kubeClient.execInPod(kafkaPodName, "/bin/bash", "-c", "cat /opt/kafka/rack/rack.id").out();
        assertEquals("zone", rackId);

        String brokerRack = kubeClient.execInPod(kafkaPodName, "/bin/bash", "-c", "cat /tmp/strimzi.properties | grep broker.rack").out();
        assertTrue(brokerRack.contains("broker.rack=zone"));

        List<Event> events = getEvents("Pod", kafkaPodName);
        assertThat(events, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(events, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));
    }

    /**
     * Test the case where the TO is configured to watch a different namespace that it is deployed in
     */
    @Test
    //@JUnitGroup(name = "regression")
    @KafkaFromClasspathYaml
    @Namespace(value = "topic-operator-namespace", use = false)
    public void testWatchingOtherNamespace() throws InterruptedException {
        List<String> topics = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
        assertThat(topics, not(hasItems("my-topic")));
        String origNamespace = kubeClient.namespace("topic-operator-namespace");
        kubeClient.create(new File("../examples/topic/kafka-topic.yaml"));
        Thread.sleep(10_000);
        kubeClient.namespace(origNamespace);
        topics = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
        assertThat(topics, hasItems("my-topic"));
    }
}

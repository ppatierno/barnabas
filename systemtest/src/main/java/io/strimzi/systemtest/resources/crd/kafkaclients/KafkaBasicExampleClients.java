/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources.crd.kafkaclients;

import io.fabric8.kubernetes.api.model.batch.DoneableJob;
import io.fabric8.kubernetes.api.model.batch.JobBuilder;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.KubernetesResource;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.utils.ClientUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.Map;

public abstract class KafkaBasicExampleClients {

    private static final Logger LOGGER = LogManager.getLogger(KafkaBasicExampleClients.class);

    protected String producerName;
    protected String consumerName;
    protected String bootstrapServer;
    protected String topicName;
    protected int messageCount;
    protected String additionalConfig;
    protected String consumerGroup;
    protected long delayMs;

    public abstract static class KafkaBasicClientsBuilder<T extends KafkaBasicClientsBuilder<T>> {
        private String producerName;
        private String consumerName;
        private String bootstrapServer;
        private String topicName;
        private int messageCount;
        private String additionalConfig;
        private String consumerGroup;
        private long delayMs;

        public T withProducerName(String producerName) {
            this.producerName = producerName;
            return self();
        }

        public T withConsumerName(String consumerName) {
            this.consumerName = consumerName;
            return self();
        }

        public T withBootstrapServer(String bootstrapServer) {
            this.bootstrapServer = bootstrapServer;
            return self();
        }

        public T withTopicName(String topicName) {
            this.topicName = topicName;
            return self();
        }

        public T withMessageCount(int messageCount) {
            this.messageCount = messageCount;
            return self();
        }

        public T withAdditionalConfig(String additionalConfig) {
            this.additionalConfig = additionalConfig;
            return self();
        }

        public T withConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
            return self();
        }

        public T withDelayMs(long delayMs) {
            this.delayMs = delayMs;
            return self();
        }

        protected abstract KafkaBasicExampleClients build();

        protected abstract T self();
    }

    protected KafkaBasicExampleClients(KafkaBasicClientsBuilder<?> builder) {
        if (builder.topicName == null || builder.topicName.isEmpty()) throw new InvalidParameterException("Topic name is not set.");
        if (builder.bootstrapServer == null || builder.bootstrapServer.isEmpty()) throw new InvalidParameterException("Bootstrap server is not set.");
        if (builder.messageCount <= 0) throw  new InvalidParameterException("Message count is less than 1");
        if (builder.consumerGroup == null || builder.consumerGroup.isEmpty()) {
            LOGGER.info("Consumer group were not specified going to create the random one.");
            builder.consumerGroup = ClientUtils.generateRandomConsumerGroup();
        }

        producerName = builder.producerName;
        consumerName = builder.consumerName;
        bootstrapServer = builder.bootstrapServer;
        topicName = builder.topicName;
        messageCount = builder.messageCount;
        additionalConfig = builder.additionalConfig;
        consumerGroup = builder.consumerGroup;
        delayMs = builder.delayMs;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }


    public DoneableJob producerStrimzi() {
        if (producerName == null || producerName.isEmpty()) throw new InvalidParameterException("Producer name is not set.");

        Map<String, String> producerLabels = new HashMap<>();
        producerLabels.put("app", producerName);
        producerLabels.put(Constants.KAFKA_CLIENTS_LABEL_KEY, Constants.KAFKA_CLIENTS_LABEL_VALUE);

        return KubernetesResource.deployNewJob(new JobBuilder()
            .withNewMetadata()
                .withNamespace(ResourceManager.kubeClient().getNamespace())
                .withLabels(producerLabels)
                .withName(producerName)
            .endMetadata()
            .withNewSpec()
                .withNewTemplate()
                    .withNewMetadata()
                        .withLabels(producerLabels)
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .withContainers()
                            .addNewContainer()
                                .withName(producerName)
                                .withImagePullPolicy(Constants.IF_NOT_PRESENT_IMAGE_PULL_POLICY)
                                .withImage("strimzi/hello-world-producer:latest")
                                .addNewEnv()
                                    .withName("BOOTSTRAP_SERVERS")
                                    .withValue(bootstrapServer)
                                .endEnv()
                                .addNewEnv()
                                    .withName("TOPIC")
                                    .withValue(topicName)
                                .endEnv()
                                .addNewEnv()
                                    .withName("DELAY_MS")
                                    .withValue(String.valueOf(delayMs))
                                .endEnv()
                                .addNewEnv()
                                    .withName("LOG_LEVEL")
                                    .withValue("DEBUG")
                                .endEnv()
                                .addNewEnv()
                                    .withName("MESSAGE_COUNT")
                                    .withValue(String.valueOf(messageCount))
                                .endEnv()
                                .addNewEnv()
                                    .withName("MESSAGE")
                                    .withValue("Hello-world")
                                .endEnv()
                                .addNewEnv()
                                    .withName("PRODUCER_ACKS")
                                    .withValue("all")
                                .endEnv()
                                .addNewEnv()
                                    .withName("ADDITIONAL_CONFIG")
                                    .withValue(additionalConfig)
                                .endEnv()
                                .addNewEnv()
                                    .withName("BLOCKING_PRODUCER")
                                    .withValue("true")
                                .endEnv()
                            .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build());
    }

    public DoneableJob consumerStrimzi() {
        if (consumerName == null || consumerName.isEmpty()) throw new InvalidParameterException("Consumer name is not set.");

        Map<String, String> consumerLabels = new HashMap<>();
        consumerLabels.put("app", consumerName);
        consumerLabels.put(Constants.KAFKA_CLIENTS_LABEL_KEY, Constants.KAFKA_CLIENTS_LABEL_VALUE);

        return KubernetesResource.deployNewJob(new JobBuilder()
            .withNewMetadata()
                .withNamespace(ResourceManager.kubeClient().getNamespace())
                .withLabels(consumerLabels)
                .withName(consumerName)
            .endMetadata()
            .withNewSpec()
            .withNewTemplate()
                .withNewMetadata()
                    .withLabels(consumerLabels)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withContainers()
                        .addNewContainer()
                            .withName(consumerName)
                            .withImagePullPolicy(Constants.IF_NOT_PRESENT_IMAGE_PULL_POLICY)
                            .withImage("strimzi/hello-world-consumer:latest")
                            .addNewEnv()
                                .withName("BOOTSTRAP_SERVERS")
                                .withValue(bootstrapServer)
                            .endEnv()
                            .addNewEnv()
                                .withName("TOPIC")
                                .withValue(topicName)
                            .endEnv()
                            .addNewEnv()
                                .withName("DELAY_MS")
                                .withValue(String.valueOf(delayMs))
                            .endEnv()
                            .addNewEnv()
                                .withName("LOG_LEVEL")
                                .withValue("DEBUG")
                            .endEnv()
                            .addNewEnv()
                                .withName("MESSAGE_COUNT")
                                .withValue(String.valueOf(messageCount))
                            .endEnv()
                            .addNewEnv()
                                .withName("GROUP_ID")
                                .withValue(consumerGroup)
                            .endEnv()
                            .addNewEnv()
                                .withName("ADDITIONAL_CONFIG")
                                .withValue(additionalConfig)
                            .endEnv()
                        .endContainer()
                .endSpec()
            .endTemplate()
            .endSpec()
            .build());
    }
}

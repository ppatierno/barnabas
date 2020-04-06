/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.kafkaclients.internalClients;

import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.kafkaclients.AbstractKafkaClient;
import io.strimzi.systemtest.kafkaclients.KafkaClientOperations;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.systemtest.kafkaclients.internalClients.ClientType.CLI_KAFKA_VERIFIABLE_CONSUMER;
import static io.strimzi.systemtest.kafkaclients.internalClients.ClientType.CLI_KAFKA_VERIFIABLE_PRODUCER;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The InternalKafkaClient for sending and receiving messages using basic properties.
 * The client is using an internal listeners and communicate from the pod.
 */
public class InternalKafkaClient extends AbstractKafkaClient implements KafkaClientOperations<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(InternalKafkaClient.class);

    private String podName;

    public static class Builder extends AbstractKafkaClient.Builder<Builder> {

        private String podName;

        public Builder withUsingPodName(String podName) {
            this.podName = podName;
            return self();
        }

        @Override
        public InternalKafkaClient build() {
            return new InternalKafkaClient(this);
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    private InternalKafkaClient(Builder builder) {
        super(builder);
        podName = builder.podName;
    }

    public Integer sendMessagesPlain() {
        return sendMessagesPlain(Constants.GLOBAL_CLIENTS_TIMEOUT);
    }

    /**
     * Method for send messages to specific kafka cluster. It uses test-client API for communication with deployed clients inside kubernetes cluster
     * @return count of send and acknowledged messages
     */
    @Override
    public Integer sendMessagesPlain(long timeout) {

        VerifiableClient producer = new VerifiableClient.VerifiableClientBuilder()
            .withClientType(CLI_KAFKA_VERIFIABLE_PRODUCER)
            .withUsingPodName(podName)
            .withPodNamespace(namespaceName)
            .withMaxMessages(messageCount)
            .withKafkaUsername(kafkaUsername)
            .withBootstrapServer(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(topicName)
            .build();

        LOGGER.info("Starting verifiableClient plain producer with following configuration {}", producer.toString());
        LOGGER.info("Sending {} messages to {}#{}", messageCount, producer.getBootstrapServer(), topicName);

        TestUtils.waitFor("Sending messages", Constants.PRODUCER_POLL_INTERVAL, Constants.GLOBAL_CLIENTS_TIMEOUT, () -> {
            LOGGER.info("Sending {} messages to {}", messageCount, podName);
            producer.run(Constants.PRODUCER_TIMEOUT);
            int sent = getSentMessagesCount(producer.getMessages().toString(), messageCount);
            return sent == messageCount;
        });

        int sent = getSentMessagesCount(producer.getMessages().toString(), messageCount);

        LOGGER.info("Producer produced {} messages", sent);

        return sent;
    }

    public Integer sendMessagesTls() {
        return sendMessagesTls(Constants.GLOBAL_CLIENTS_TIMEOUT);
    }

    @Override
    public Integer sendMessagesTls(long timeout) {

        VerifiableClient producerTls = new VerifiableClient.VerifiableClientBuilder()
            .withClientType(CLI_KAFKA_VERIFIABLE_PRODUCER)
            .withUsingPodName(podName)
            .withPodNamespace(namespaceName)
            .withMaxMessages(messageCount)
            .withKafkaUsername(kafkaUsername)
            .withBootstrapServer(KafkaResources.tlsBootstrapAddress(clusterName))
            .withTopicName(topicName)
            .build();

        LOGGER.info("Starting verifiableClient tls producer with following configuration {}", producerTls.toString());
        LOGGER.info("Sending {} messages to {}#{}", messageCount, producerTls.getBootstrapServer(), topicName);

        boolean hasPassed = producerTls.run(timeout);
        LOGGER.info("Producer finished correctly: {}", hasPassed);

        int sent = getSentMessagesCount(producerTls.getMessages().toString(), messageCount);

        LOGGER.info("Producer produced {} messages", sent);

        return sent;
    }

    public Integer receiveMessagesPlain() {
        return receiveMessagesPlain(Constants.GLOBAL_CLIENTS_TIMEOUT);
    }

    @Override
    public Integer receiveMessagesPlain(long timeout) {

        VerifiableClient consumer = new VerifiableClient.VerifiableClientBuilder()
            .withClientType(CLI_KAFKA_VERIFIABLE_CONSUMER)
            .withUsingPodName(podName)
            .withPodNamespace(namespaceName)
            .withMaxMessages(messageCount)
            .withKafkaUsername(kafkaUsername)
            .withBootstrapServer(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(topicName)
            .withConsumerGroupName(consumerGroup)
            .withConsumerInstanceId("instance" + new Random().nextInt(Integer.MAX_VALUE))
            .build();


        LOGGER.info("Starting verifiableClient plain consumer with following configuration {}", consumer.toString());
        LOGGER.info("Wait for receive {} messages from {}#{}", messageCount, consumer.getBootstrapServer(), topicName);

        boolean hasPassed = consumer.run(timeout);
        LOGGER.info("Consumer finished correctly: {}", hasPassed);

        int received = getReceivedMessagesCount(consumer.getMessages().toString());
        LOGGER.info("Consumer consumed {} messages", received);

        return received;
    }

    public Integer receiveMessagesTls() {
        return receiveMessagesTls(Constants.GLOBAL_CLIENTS_TIMEOUT);
    }

    /**
     * Method for receive messages from specific kafka cluster. It uses test-client API for communication with deployed clients inside kubernetes cluster
       * @return count of received messages
     */
    @Override
    public Integer receiveMessagesTls(long timeoutMs) {

        VerifiableClient consumerTls = new VerifiableClient.VerifiableClientBuilder()
            .withClientType(CLI_KAFKA_VERIFIABLE_CONSUMER)
            .withUsingPodName(podName)
            .withPodNamespace(namespaceName)
            .withMaxMessages(messageCount)
            .withKafkaUsername(kafkaUsername)
            .withBootstrapServer(KafkaResources.tlsBootstrapAddress(clusterName))
            .withTopicName(topicName)
            .withConsumerGroupName(consumerGroup)
            .withConsumerInstanceId("instance" + new Random().nextInt(Integer.MAX_VALUE))
            .build();

        LOGGER.info("Starting verifiableClient tls consumer with following configuration {}", consumerTls.toString());
        LOGGER.info("Wait for receive {} messages from {}#{}", messageCount, consumerTls.getBootstrapServer(), topicName);

        boolean hasPassed = consumerTls.run(timeoutMs);
        LOGGER.info("Consumer finished correctly: {}", hasPassed);

        int received = getReceivedMessagesCount(consumerTls.getMessages().toString());
        LOGGER.info("Consumer consumed {} messages", received);

        return received;
    }

    public void checkProducedAndConsumedMessages(int producedMessages, int consumedMessages) {
        assertSentAndReceivedMessages(producedMessages, consumedMessages);
    }

    /**
     * Assert count of sent and received messages
     * @param sent count of sent messages
     * @param received count of received messages
     */
    public void assertSentAndReceivedMessages(int sent, int received) {
        assertThat(String.format("Sent (%s) and receive (%s) message count is not equal", sent, received),
            sent == received);
    }

    /**
     * Get sent messages fro object response
     * @param response response
     * @param messageCount expected message count
     * @return count of acknowledged messages
     */
    private int getSentMessagesCount(String response, int messageCount) {
        int sentMessages;
        Pattern r = Pattern.compile("sent\":(" + messageCount + ")");
        Matcher m = r.matcher(response);
        sentMessages = m.find() ? Integer.parseInt(m.group(1)) : -1;

        r = Pattern.compile("acked\":(" + messageCount + ")");
        m = r.matcher(response);

        if (m.find()) {
            return sentMessages == Integer.parseInt(m.group(1)) ? sentMessages : -1;
        } else {
            return -1;
        }
    }

    /**
     * Get recieved message count from object response
     * @param response response
     * @return count of received messages
     */
    private int getReceivedMessagesCount(String response) {
        int receivedMessages = 0;
        Pattern r = Pattern.compile("records_consumed\",\"count\":([0-9]*)");
        Matcher m = r.matcher(response);

        while (m.find()) {
            receivedMessages += Integer.parseInt(m.group(1));
        }

        return receivedMessages;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getPodName() {
        return podName;
    }
}

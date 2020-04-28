/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kafkaUtils;

import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class KafkaUserUtils {

    private static final Logger LOGGER = LogManager.getLogger(KafkaUserUtils.class);

    private KafkaUserUtils() {}

    public static void waitForKafkaUserCreation(String userName) {
        KafkaUser kafkaUser = KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get();

        SecretUtils.waitForSecretReady(userName,
            () -> LOGGER.info(KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get()));

        ResourceManager.waitForResourceStatus(KafkaUserResource.kafkaUserClient(), kafkaUser, "Ready");
    }

    public static void waitForKafkaUserDeletion(String userName) {
        LOGGER.info("Waiting for KafkaUser deletion {}", userName);
        TestUtils.waitFor("KafkaUser deletion " + userName, Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get() == null,
            () -> LOGGER.info(KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get())
        );
        LOGGER.info("KafkaUser {} deleted", userName);
    }

    public static void waitForKafkaUserIncreaseObserverGeneration(long observation, String userName) {
        TestUtils.waitFor("increase observation generation from " + observation + " for user " + userName,
            Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_SECRET_CREATION,
            () -> observation < KafkaUserResource.kafkaUserClient()
                .inNamespace(kubeClient().getNamespace()).withName(userName).get().getStatus().getObservedGeneration());
    }

    public static void waitUntilKafkaUserStatusConditionIsPresent(String userName) {
        LOGGER.info("Wait until KafkaUser {} status is available", userName);
        TestUtils.waitFor("KafkaUser " + userName + " status is available", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT,
            () -> KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get().getStatus().getConditions() != null,
            () -> LOGGER.info(KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get())
        );
        LOGGER.info("KafkaUser {} status is available", userName);
    }

    /**
     * Wait until KafkaUser is in desired state
     * @param userName name of KafkaUser
     * @param state desired state
     */
    public static void waitForKafkaUserStatus(String userName, String state) {
        KafkaUser kafkaUser = KafkaUserResource.kafkaUserClient().inNamespace(kubeClient().getNamespace()).withName(userName).get();
        ResourceManager.waitForResourceStatus(KafkaUserResource.kafkaUserClient(), kafkaUser, state);
    }

    public static void waitForKafkaUserReady(String userName) {
        waitForKafkaUserStatus(userName, "Ready");
    }

    public static void waitForKafkaUserNotReady(String userName) {
        waitForKafkaUserStatus(userName, "NotReady");
    }
}

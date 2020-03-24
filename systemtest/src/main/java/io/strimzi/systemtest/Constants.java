/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import java.time.Duration;

/**
 * Interface for keep global constants used across system tests.
 */
public interface Constants {
    long TIMEOUT_FOR_DEPLOYMENT_CONFIG_READINESS = Duration.ofMinutes(7).toMillis();
    long TIMEOUT_FOR_RESOURCE_CREATION = Duration.ofMinutes(5).toMillis();
    long TIMEOUT_FOR_SECRET_CREATION = Duration.ofMinutes(2).toMillis();
    long TIMEOUT_FOR_RESOURCE_READINESS = Duration.ofMinutes(14).toMillis();
    long TIMEOUT_FOR_RESOURCE_DELETION = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_FOR_POD_DELETION = Duration.ofMinutes(5).toMillis();
    long TIMEOUT_FOR_CR_CREATION = Duration.ofMinutes(3).toMillis();
    long TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS = Duration.ofMinutes(7).toMillis();
    long TIMEOUT_FOR_MIRROR_JOIN_TO_GROUP = Duration.ofMinutes(2).toMillis();
    long TIMEOUT_FOR_TOPIC_CREATION = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_FOR_LOG = Duration.ofMinutes(2).toMillis();
    long POLL_INTERVAL_FOR_RESOURCE_CREATION = Duration.ofSeconds(3).toMillis();
    long POLL_INTERVAL_FOR_RESOURCE_READINESS = Duration.ofSeconds(1).toMillis();
    long POLL_INTERVAL_FOR_RESOURCE_DELETION = Duration.ofSeconds(5).toMillis();
    long WAIT_FOR_ROLLING_UPDATE_INTERVAL = Duration.ofSeconds(5).toMillis();
    long WAIT_FOR_ROLLING_UPDATE_TIMEOUT = Duration.ofMinutes(7).toMillis();

    long TIMEOUT_FOR_SEND_RECEIVE_MSG = Duration.ofSeconds(60).toMillis();
    long TIMEOUT_AVAILABILITY_TEST = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_SEND_MESSAGES = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_RECV_MESSAGES = Duration.ofMinutes(1).toMillis();

    long TIMEOUT_FOR_CLUSTER_STABLE = Duration.ofMinutes(20).toMillis();
    long TIMEOUT_FOR_ZK_CLUSTER_STABILIZATION = Duration.ofMinutes(7).toMillis();

    long GET_BROKER_API_TIMEOUT = Duration.ofMinutes(1).toMillis();
    long GET_BROKER_API_INTERVAL = Duration.ofSeconds(5).toMillis();
    long TIMEOUT_FOR_GET_SECRETS = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_TEARDOWN = Duration.ofSeconds(10).toMillis();
    long GLOBAL_TIMEOUT = Duration.ofMinutes(5).toMillis();
    long GLOBAL_STATUS_TIMEOUT = Duration.ofMinutes(3).toMillis();
    long CONNECT_STATUS_TIMEOUT = Duration.ofMinutes(5).toMillis();
    long GLOBAL_POLL_INTERVAL = Duration.ofSeconds(1).toMillis();
    long PRODUCER_POLL_INTERVAL = Duration.ofSeconds(30).toMillis();
    long PRODUCER_TIMEOUT = Duration.ofSeconds(25).toMillis();

    long GLOBAL_TRACING_POLL = Duration.ofSeconds(30).toMillis();
    long GLOBAL_TRACING_TIMEOUT =  Duration.ofMinutes(7).toMillis();

    long GLOBAL_CLIENTS_TIMEOUT = Duration.ofMinutes(2).toMillis();
    long GLOBAL_CLIENTS_EXCEPT_ERROR_TIMEOUT = Duration.ofSeconds(10).toMillis();

    long CO_OPERATION_TIMEOUT_DEFAULT = Duration.ofMinutes(5).toMillis();
    long CO_OPERATION_TIMEOUT_SHORT = Duration.ofSeconds(30).toMillis();
    long CO_OPERATION_TIMEOUT_MEDIUM = Duration.ofMinutes(2).toMillis();
    long CO_OPERATION_TIMEOUT_WAIT = CO_OPERATION_TIMEOUT_SHORT + Duration.ofSeconds(80).toMillis();
    long CO_OPERATION_TIMEOUT_POLL = Duration.ofSeconds(2).toMillis();
    long RECONCILIATION_INTERVAL = Duration.ofSeconds(30).toMillis();

    // stability count ensures that after some reconciliation we have some additional time
    int GLOBAL_STABILITY_OFFSET_COUNT = 20;
    // it is replacement instead of checking logs for reconciliation using dynamic waiting on some change for some period of time
    int GLOBAL_RECONCILIATION_COUNT = (int) ((RECONCILIATION_INTERVAL / GLOBAL_POLL_INTERVAL) + GLOBAL_STABILITY_OFFSET_COUNT);

    String KAFKA_CLIENTS = "kafka-clients";
    String STRIMZI_DEPLOYMENT_NAME = "strimzi-cluster-operator";
    String ALWAYS_IMAGE_PULL_POLICY = "Always";
    String IF_NOT_PRESENT_IMAGE_PULL_POLICY = "IfNotPresent";

    int HTTP_KEYCLOAK_DEFAULT_PORT = 8080;
    int HTTPS_KEYCLOAK_DEFAULT_PORT = 8443;

    String DEPLOYMENT = "Deployment";
    String SERVICE = "Service";
    String INGRESS = "Ingress";
    String CLUSTER_ROLE_BINDING = "ClusterRoleBinding";
    String ROLE_BINDING = "RoleBinding";

    /**
     * Kafka Bridge JSON encoding with JSON embedded format
     */
    String KAFKA_BRIDGE_JSON_JSON = "application/vnd.kafka.json.v2+json";

    /**
     * Kafka Bridge JSON encoding
     */
    String KAFKA_BRIDGE_JSON = "application/vnd.kafka.v2+json";

    String DEFAULT_SINK_FILE_PATH = "/tmp/test-file-sink.txt";

    int HTTP_BRIDGE_DEFAULT_PORT = 8080;
    int HTTP_JAEGER_DEFAULT_TCP_PORT = 5778;
    int HTTP_JAEGER_DEFAULT_NODE_PORT = 32480;
    int HTTPS_KEYCLOAK_DEFAULT_NODE_PORT = 32481;
    int HTTP_KEYCLOAK_DEFAULT_NODE_PORT = 32482;

    /**
     * Default value which allows execution of tests with any tags
     */
    String DEFAULT_TAG = "all";

    /**
     * Tag for acceptance tests, which are triggered for each push/pr/merge on travis-ci
     */
    String ACCEPTANCE = "acceptance";

    /**
     * Tag for regression tests which are stable.
     */
    String REGRESSION = "regression";

    /**
     * Tag for upgrade tests.
     */
    String UPGRADE = "upgrade";

    /**
     * Tag for acceptance tests executed during Travis builds.
     */
    String TRAVIS = "travis";

    /**
     * Tag for tests, which results are not 100% reliable on all testing environments.
     */
    String FLAKY = "flaky";

    /**
     * Tag for scalability tests
     */
    String SCALABILITY = "scalability";

    /**
     * Tag for tests, which are working only on specific environment and we usually don't want to execute them on all environments.
     */
    String SPECIFIC = "specific";

    /**
     * Tag for tests, which are using NodePort.
     */
    String NODEPORT_SUPPORTED = "nodeport";

    /**
     * Tag for tests, which are using LoadBalancer.
     */
    String LOADBALANCER_SUPPORTED = "loadbalancer";

    /**
     * Tag for tests, which are using NetworkPolicies.
     */
    String NETWORKPOLICIES_SUPPORTED = "networkpolicies";

    /**
     * Tag for Prometheus tests
     */
    String PROMETHEUS = "prometheus";

    /**
     * Tag for Tracing tests
     */
    String TRACING = "tracing";

    /**
     * Tag for Helm tests
     */
    String HELM = "helm";

    /**
     * Tag for oauth tests
     */
    String OAUTH = "oauth";

    /**
     * Tag for recovery tests
     */
    String RECOVERY = "recovery";

    /**
     * Tag for tests which deploys KafkaConnector resource
     */
    String CONNECTOR_OPERATOR = "connectoroperator";

    /**
     * Tag for tests which deploys KafkaConnect resource
     */
    String CONNECT = "connect";

    /**
     * Tag for tests which deploys KafkaConnectS2I resource
     */
    String CONNECT_S2I = "connects2i";

    /**
     * Tag for tests which deploys KafkaMirrorMaker resource
     */
    String MIRROR_MAKER = "mirrormaker";

    /**
     * Tag for tests which deploys KafkaMirrorMaker2 resource
     */
    String MIRROR_MAKER2 = "mirrormaker2";

    /**
     * Tag for tests which deploys any of KafkaConnect, KafkaConnects2i, KafkaConnector, KafkaMirrorMaker2
     */
    String CONNECT_COMPONENTS = "connectcomponents";

    /**
     * Tag for tests which deploys KafkaBridge resource
     */
    String BRIDGE = "bridge";

    /**
     * Tag for tests which use internal Kafka clients (used clients in cluster)
     */
    String INTERNAL_CLIENTS_USED = "internalclients";

    /**
     * Tag for tests which use external Kafka clients (called from test code)
     */
    String EXTERNAL_CLIENTS_USED = "externalclients";
}

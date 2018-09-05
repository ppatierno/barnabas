/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.KafkaConnectS2IAssemblyList;
import io.strimzi.api.kafka.model.DoneableKafkaConnectS2I;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.cluster.model.KafkaConnectS2ICluster;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.resource.BuildConfigOperator;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.DeploymentConfigOperator;
import io.strimzi.operator.common.operator.resource.ImageStreamOperator;
import io.strimzi.operator.common.operator.resource.NetworkPolicyOperator;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * <p>Assembly operator for a "Kafka Connect S2I" assembly, which manages:</p>
 * <ul>
 *     <li>A Kafka Connect Deployment and related Services</li>
 *     <li>An ImageBuildStream</li>
 *     <li>A BuildConfig</li>
 * </ul>
 */
public class KafkaConnectS2IAssemblyOperator extends AbstractAssemblyOperator<OpenShiftClient, KafkaConnectS2I, KafkaConnectS2IAssemblyList, DoneableKafkaConnectS2I, Resource<KafkaConnectS2I, DoneableKafkaConnectS2I>> {

    private static final Logger log = LogManager.getLogger(KafkaConnectS2IAssemblyOperator.class.getName());
    private final ServiceOperator serviceOperations;
    private final DeploymentConfigOperator deploymentConfigOperations;
    private final ImageStreamOperator imagesStreamOperations;
    private final BuildConfigOperator buildConfigOperations;
    private final ConfigMapOperator configMapOperations;

    /**
     * @param vertx                      The Vertx instance
     * @param isOpenShift                Whether we're running with OpenShift
     * @param configMapOperations        For operating on ConfigMaps
     * @param deploymentConfigOperations For operating on Deployments
     * @param serviceOperations          For operating on Services
     * @param imagesStreamOperations     For operating on ImageStreams, may be null
     * @param buildConfigOperations      For operating on BuildConfigs, may be null
     * @param secretOperations           For operating on Secrets
     */
    public KafkaConnectS2IAssemblyOperator(Vertx vertx, boolean isOpenShift,
                                           CertManager certManager,
                                           CrdOperator<OpenShiftClient, KafkaConnectS2I, KafkaConnectS2IAssemblyList, DoneableKafkaConnectS2I> connectOperator,
                                           ConfigMapOperator configMapOperations,
                                           DeploymentConfigOperator deploymentConfigOperations,
                                           ServiceOperator serviceOperations,
                                           ImageStreamOperator imagesStreamOperations,
                                           BuildConfigOperator buildConfigOperations,
                                           SecretOperator secretOperations,
                                           NetworkPolicyOperator networkPolicyOperator) {
        super(vertx, isOpenShift, ResourceType.CONNECT_S2I, certManager, connectOperator, secretOperations, networkPolicyOperator);
        this.configMapOperations = configMapOperations;
        this.serviceOperations = serviceOperations;
        this.deploymentConfigOperations = deploymentConfigOperations;
        this.imagesStreamOperations = imagesStreamOperations;
        this.buildConfigOperations = buildConfigOperations;
    }

    @Override
    public Future<Void> createOrUpdate(Reconciliation reconciliation, KafkaConnectS2I kafkaConnectS2I) {
        String namespace = reconciliation.namespace();
        if (isOpenShift) {
            KafkaConnectS2ICluster connect;
            try {
                connect = KafkaConnectS2ICluster.fromCrd(kafkaConnectS2I);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            connect.generateBuildConfig();
            ConfigMap logAndMetricsConfigMap = connect.generateMetricsAndLogConfigMap(connect.getLogging() instanceof ExternalLogging ?
                    configMapOperations.get(namespace, ((ExternalLogging) connect.getLogging()).getName()) :
                    null);

            HashMap<String, String> annotations = new HashMap();
            annotations.put("strimzi.io/logging", logAndMetricsConfigMap.getData().get(connect.ANCILLARY_CM_KEY_LOG_CONFIG));

            return deploymentConfigOperations.scaleDown(namespace, connect.getName(), connect.getReplicas())
                    .compose(scale -> serviceOperations.reconcile(namespace, connect.getServiceName(), connect.generateService()))
                    .compose(i -> configMapOperations.reconcile(namespace, connect.getAncillaryConfigName(), logAndMetricsConfigMap))
                    .compose(i -> deploymentConfigOperations.reconcile(namespace, connect.getName(), connect.generateDeploymentConfig(annotations)))
                    .compose(i -> imagesStreamOperations.reconcile(namespace, connect.getSourceImageStreamName(), connect.generateSourceImageStream()))
                    .compose(i -> imagesStreamOperations.reconcile(namespace, connect.getName(), connect.generateTargetImageStream()))
                    .compose(i -> buildConfigOperations.reconcile(namespace, connect.getName(), connect.generateBuildConfig()))
                    .compose(i -> deploymentConfigOperations.scaleUp(namespace, connect.getName(), connect.getReplicas()).map((Void) null));
        } else {
            return Future.failedFuture("S2I only available on OpenShift");
        }
    }

    @Override
    protected Future<Void> delete(Reconciliation reconciliation) {
        if (isOpenShift) {
            String namespace = reconciliation.namespace();
            String assemblyName = reconciliation.name();
            String clusterName = KafkaConnectS2ICluster.kafkaConnectClusterName(assemblyName);

            return CompositeFuture.join(serviceOperations.reconcile(namespace, KafkaConnectS2ICluster.serviceName(assemblyName), null),
                configMapOperations.reconcile(namespace, KafkaConnectS2ICluster.logAndMetricsConfigName(assemblyName), null),
                deploymentConfigOperations.reconcile(namespace, clusterName, null),
                imagesStreamOperations.reconcile(namespace, KafkaConnectS2ICluster.getSourceImageStreamName(clusterName), null),
                imagesStreamOperations.reconcile(namespace, clusterName, null),
                buildConfigOperations.reconcile(namespace, clusterName, null))
            .map((Void) null);
        } else {
            return Future.failedFuture("S2I only available on OpenShift");
        }
    }

    @Override
    protected List<HasMetadata> getResources(String namespace, Labels selector) {
        List<HasMetadata> result = new ArrayList<>();
        result.addAll(serviceOperations.list(namespace, selector));
        result.addAll(deploymentConfigOperations.list(namespace, selector));
        result.addAll(imagesStreamOperations.list(namespace, selector));
        result.addAll(buildConfigOperations.list(namespace, selector));
        result.addAll(resourceOperator.list(namespace, selector));
        return result;
    }

}

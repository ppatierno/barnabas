/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.KafkaConnectS2IList;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.DoneableKafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnectSpec;
import io.strimzi.api.kafka.model.status.KafkaConnectStatus;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.KafkaConnectBuild;
import io.strimzi.operator.cluster.model.KafkaConnectCluster;
import io.strimzi.operator.cluster.model.KafkaConnectDockerfile;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationException;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.operator.resource.BuildConfigOperator;
import io.strimzi.operator.common.operator.resource.BuildOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.NetworkPolicyOperator;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * <p>Assembly operator for a "Kafka Connect" assembly, which manages:</p>
 * <ul>
 *     <li>A Kafka Connect Deployment and related Services</li>
 * </ul>
 */
public class KafkaConnectAssemblyOperator extends AbstractConnectOperator<KubernetesClient, KafkaConnect, KafkaConnectList, DoneableKafkaConnect, Resource<KafkaConnect, DoneableKafkaConnect>, KafkaConnectSpec, KafkaConnectStatus> {
    private static final Logger log = LogManager.getLogger(KafkaConnectAssemblyOperator.class.getName());
    private final DeploymentOperator deploymentOperations;
    private final NetworkPolicyOperator networkPolicyOperator;
    private final PodOperator podOperator;
    private final BuildConfigOperator buildConfigOperator;
    private final BuildOperator buildOperator;
    private final KafkaVersion.Lookup versions;
    private final CrdOperator<OpenShiftClient, KafkaConnectS2I, KafkaConnectS2IList, DoneableKafkaConnectS2I> connectS2IOperations;
    private final ClusterOperatorConfig.RbacScope rbacScope;
    protected final long connectBuildTimeoutMs;

    /**
     * @param vertx The Vertx instance
     * @param pfa Platform features availability properties
     * @param supplier Supplies the operators for different resources
     * @param config ClusterOperator configuration. Used to get the user-configured image pull policy and the secrets.
     */
    public KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config) {
        this(vertx, pfa, supplier, config, connect -> new KafkaConnectApiImpl(vertx));
    }

    public KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config,
                                        Function<Vertx, KafkaConnectApi> connectClientProvider) {
        this(vertx, pfa, supplier, config, connectClientProvider, KafkaConnectCluster.REST_API_PORT);
    }
    public KafkaConnectAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config,
                                        Function<Vertx, KafkaConnectApi> connectClientProvider, int port) {
        super(vertx, pfa, KafkaConnect.RESOURCE_KIND, supplier.connectOperator, supplier, config, connectClientProvider, port);
        this.deploymentOperations = supplier.deploymentOperations;
        this.connectS2IOperations = supplier.connectS2IOperator;
        this.networkPolicyOperator = supplier.networkPolicyOperator;
        this.podOperator = supplier.podOperations;
        this.buildConfigOperator = supplier.buildConfigOperations;
        this.buildOperator = supplier.buildOperations;

        this.versions = config.versions();
        this.rbacScope = config.getRbacScope();
        this.connectBuildTimeoutMs = config.getConnectBuildTimeoutMs();
    }

    @Override
    protected Future<KafkaConnectStatus> createOrUpdate(Reconciliation reconciliation, KafkaConnect kafkaConnect) {
        BuildState buildState = new BuildState();
        KafkaConnectCluster connect;
        KafkaConnectBuild build;
        KafkaConnectStatus kafkaConnectStatus = new KafkaConnectStatus();
        try {
            connect = KafkaConnectCluster.fromCrd(kafkaConnect, versions);
            build = KafkaConnectBuild.fromCrd(kafkaConnect, versions);
        } catch (Exception e) {
            StatusUtils.setStatusConditionAndObservedGeneration(kafkaConnect, kafkaConnectStatus, Future.failedFuture(e));
            return Future.failedFuture(new ReconciliationException(kafkaConnectStatus, e));
        }

        Promise<KafkaConnectStatus> createOrUpdatePromise = Promise.promise();
        String namespace = reconciliation.namespace();

        Map<String, String> annotations = new HashMap<>(2);

        log.debug("{}: Updating Kafka Connect cluster", reconciliation);

        Future<KafkaConnectS2I> connectS2ICheck;
        if (connectS2IOperations != null)   {
            connectS2ICheck = connectS2IOperations.getAsync(kafkaConnect.getMetadata().getNamespace(), kafkaConnect.getMetadata().getName());
        } else {
            connectS2ICheck = Future.succeededFuture(null);
        }

        boolean connectHasZeroReplicas = connect.getReplicas() == 0;

        final AtomicReference<String> desiredLogging = new AtomicReference<>();
        connectS2ICheck
                .compose(otherConnect -> {
                    if (otherConnect != null
                            // There is a KafkaConnectS2I with the same name which is older than this KafkaConnect
                            && kafkaConnect.getMetadata().getCreationTimestamp().compareTo(otherConnect.getMetadata().getCreationTimestamp()) > 0)    {
                        return Future.failedFuture("Both KafkaConnect and KafkaConnectS2I exist with the same name. " +
                                "KafkaConnectS2I is older and will be used while this custom resource will be ignored.");
                    } else {
                        return Future.succeededFuture();
                    }
                })
                .compose(i -> connectServiceAccount(namespace, connect))
                .compose(i -> connectInitClusterRoleBinding(namespace, kafkaConnect.getMetadata().getName(), connect))
                .compose(i -> networkPolicyOperator.reconcile(namespace, connect.getName(), connect.generateNetworkPolicy(pfa.isNamespaceAndPodSelectorNetworkPolicySupported(), isUseResources(kafkaConnect), operatorNamespace, operatorNamespaceLabels)))
                .compose(i -> deploymentOperations.getAsync(namespace, connect.getName()))
                .compose(deployment -> {
                    if (deployment != null) {
                        // Extract information from the current deployment. This is used to figure out if new build needs to be run or not.
                        buildState.currentBuildRevision = Annotations.stringAnnotation(deployment.getSpec().getTemplate(), Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, null);
                        buildState.currentImage = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
                        buildState.forceRebuild = Annotations.hasAnnotation(deployment, Annotations.STRIMZI_IO_CONNECT_FORCE_REBUILD);
                    }

                    return Future.succeededFuture();
                })
                .compose(i -> connectBuild(namespace, build, buildState))
                .compose(i -> deploymentOperations.scaleDown(namespace, connect.getName(), connect.getReplicas()))
                .compose(scale -> serviceOperations.reconcile(namespace, connect.getServiceName(), connect.generateService()))
                .compose(i -> connectMetricsAndLoggingConfigMap(namespace, connect))
                .compose(metricsAndLoggingCm -> {
                    ConfigMap logAndMetricsConfigMap = connect.generateMetricsAndLogConfigMap(metricsAndLoggingCm);
                    annotations.put(Annotations.ANNO_STRIMZI_LOGGING_DYNAMICALLY_UNCHANGEABLE_HASH,
                            Util.stringHash(Util.getLoggingDynamicallyUnmodifiableEntries(logAndMetricsConfigMap.getData().get(AbstractModel.ANCILLARY_CM_KEY_LOG_CONFIG))));
                    desiredLogging.set(logAndMetricsConfigMap.getData().get(AbstractModel.ANCILLARY_CM_KEY_LOG_CONFIG));
                    return configMapOperations.reconcile(namespace, connect.getAncillaryConfigMapName(), logAndMetricsConfigMap);
                })
                .compose(i -> kafkaConnectJmxSecret(namespace, kafkaConnect.getMetadata().getName(), connect))
                .compose(i -> podDisruptionBudgetOperator.reconcile(namespace, connect.getName(), connect.generatePodDisruptionBudget()))
                .compose(i -> {
                    if (buildState.desiredBuildRevision != null) {
                        annotations.put(Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, buildState.desiredBuildRevision);
                    }

                    Deployment dep = connect.generateDeployment(annotations, pfa.isOpenshift(), imagePullPolicy, imagePullSecrets);

                    if (buildState.desiredImage != null) {
                        dep.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(buildState.desiredImage);
                    }

                    return deploymentOperations.reconcile(namespace, connect.getName(), dep);
                })
                .compose(i -> deploymentOperations.scaleUp(namespace, connect.getName(), connect.getReplicas()))
                .compose(i -> deploymentOperations.waitForObserved(namespace, connect.getName(), 1_000, operationTimeoutMs))
                .compose(i -> connectHasZeroReplicas ? Future.succeededFuture() : deploymentOperations.readiness(namespace, connect.getName(), 1_000, operationTimeoutMs))
                .compose(i -> reconcileConnectors(reconciliation, kafkaConnect, kafkaConnectStatus, connectHasZeroReplicas, desiredLogging.get(), connect.getDefaultLogConfig()))
                .onComplete(reconciliationResult -> {
                    StatusUtils.setStatusConditionAndObservedGeneration(kafkaConnect, kafkaConnectStatus, reconciliationResult);

                    if (!connectHasZeroReplicas) {
                        kafkaConnectStatus.setUrl(KafkaConnectResources.url(connect.getCluster(), namespace, KafkaConnectCluster.REST_API_PORT));
                    }

                    kafkaConnectStatus.setReplicas(connect.getReplicas());
                    kafkaConnectStatus.setLabelSelector(connect.getSelectorLabels().toSelectorString());

                    if (reconciliationResult.succeeded())   {
                        createOrUpdatePromise.complete(kafkaConnectStatus);
                    } else {
                        createOrUpdatePromise.fail(new ReconciliationException(kafkaConnectStatus, reconciliationResult.cause()));
                    }
                });

        return createOrUpdatePromise.future();
    }

    @Override
    protected KafkaConnectStatus createStatus() {
        return new KafkaConnectStatus();
    }

    private Future<ReconcileResult<ServiceAccount>> connectServiceAccount(String namespace, KafkaConnectCluster connect) {
        return serviceAccountOperations.reconcile(namespace,
                KafkaConnectResources.serviceAccountName(connect.getCluster()),
                connect.generateServiceAccount());
    }

    /**
     * Creates (or deletes) the ClusterRoleBinding required for the init container used for client rack-awareness.
     * The init-container needs to be able to read the labels from the node it is running on to be able to determine
     * the `client.rack` option.
     *
     * @param namespace         Namespace of the service account to which the ClusterRole should be bound
     * @param name              Name of the ClusterRoleBinding
     * @param connectCluster    Name of the Connect cluster
     * @return                  Future for tracking the asynchronous result of the ClusterRoleBinding reconciliation
     */
    Future<ReconcileResult<ClusterRoleBinding>> connectInitClusterRoleBinding(String namespace, String name, KafkaConnectCluster connectCluster) {
        ClusterRoleBinding desired = connectCluster.generateClusterRoleBinding();

        return withIgnoreRbacError(
                clusterRoleBindingOperations.reconcile(
                        KafkaConnectResources.initContainerClusterRoleBindingName(name, namespace),
                        desired),
                desired
        );
    }

    /**
     * Deletes the ClusterRoleBinding which as a cluster-scoped resource cannot be deleted by the ownerReference
     *
     * @param reconciliation    The Reconciliation identification
     * @return                  Future indicating the result of the deletion
     */
    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        return super.delete(reconciliation)
                .compose(i -> withIgnoreRbacError(clusterRoleBindingOperations.reconcile(KafkaConnectResources.initContainerClusterRoleBindingName(reconciliation.name(), reconciliation.namespace()), null), null))
                .map(Boolean.FALSE); // Return FALSE since other resources are still deleted by garbage collection
    }

    /**
     * Builds a new container image with connectors on Kubernetes using Kaniko or on OpenShift using BuildConfig
     *
     * @param namespace         Namespace of the Connect cluster
     * @param connectBuild             KafkaConnectBuild object
     * @return                  Future for tracking the asynchronous result of the Kubernetes image build
     */
    Future<Void> connectBuild(String namespace, KafkaConnectBuild connectBuild, BuildState buildState) {
        if (connectBuild.getBuild() != null) {
            // Build exists => let's build
            KafkaConnectDockerfile dockerfile = connectBuild.generateDockerfile();
            String newBuildRevision = dockerfile.hashStub();
            ConfigMap dockerFileConfigMap = connectBuild.generateDockerfileConfigMap(dockerfile);

            if (newBuildRevision.equals(buildState.currentBuildRevision)
                    && !buildState.forceRebuild) {
                // The revision is the same and rebuild was not forced => nothing to do
                log.info("Build configuration did not changed. Nothing new to build. Container image {} will be used.", buildState.currentImage);
                buildState.desiredImage = buildState.currentImage;
                buildState.desiredBuildRevision = newBuildRevision;
                return Future.succeededFuture();
            } else if (pfa.supportsS2I()) {
                // Revisions differ and we have S2I support => we are on OpenShift and should do a build
                return openShiftBuild(namespace, connectBuild, buildState, dockerfile, newBuildRevision);
            } else {
                // Revisions differ and no S2I support => we are on Kubernetes and should do a build
                return kubernetesBuild(namespace, connectBuild, buildState, dockerFileConfigMap, newBuildRevision);
            }
        } else {
            // Build is not configured => we should delete resources
            buildState.desiredBuildRevision = null;
            return configMapOperations.reconcile(namespace, KafkaConnectResources.dockerFileConfigMapName(connectBuild.getCluster()), null)
                    .compose(ignore -> podOperator.reconcile(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), null))
                    .compose(ignore -> pfa.supportsS2I() ? buildConfigOperator.reconcile(namespace, KafkaConnectResources.buildConfigName(connectBuild.getCluster()), null) : Future.succeededFuture())
                    .mapEmpty();
        }
    }

    /**
     * Executes the Kafka Connect Build on Kubernetes. Run only if needed because of changes to the Dockerfile or when
     * triggered by annotation.
     *
     * @param namespace             Namespace where the Kafka Connect is deployed
     * @param connectBuild          The KafkaConnectBuild model with the build definitions
     * @param dockerFileConfigMap   ConfigMap with the generated Dockerfile
     * @param newBuildRevision      New build revision (hash of the Dockerfile)
     *
     * @return                      Future which completes when the build is finished (or fails if it fails)
     */
    private Future<Void> kubernetesBuild(String namespace, KafkaConnectBuild connectBuild, BuildState buildState, ConfigMap dockerFileConfigMap, String newBuildRevision)  {
        return podOperator.reconcile(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), null)
                .compose(ignore -> pfa.supportsS2I() ? buildConfigOperator.reconcile(namespace, KafkaConnectResources.buildConfigName(connectBuild.getCluster()), null) : Future.succeededFuture())
                .compose(ignore -> configMapOperations.reconcile(namespace, KafkaConnectResources.dockerFileConfigMapName(connectBuild.getCluster()), dockerFileConfigMap))
                .compose(ignore -> podOperator.reconcile(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), connectBuild.generateBuilderPod(pfa.isOpenshift(), imagePullPolicy, imagePullSecrets)))
                .compose(ignore -> podOperator.waitFor(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), "complete", 1_000, connectBuildTimeoutMs, (ignore1, ignore2) -> {
                    Pod buildPod = podOperator.get(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()));

                    // Check if build is complete now (either with success or failure)
                    return buildPod.getStatus() != null
                            && buildPod.getStatus().getContainerStatuses() != null
                            && buildPod.getStatus().getContainerStatuses().size() > 0
                            && buildPod.getStatus().getContainerStatuses().get(0) != null
                            && buildPod.getStatus().getContainerStatuses().get(0).getState() != null
                            && buildPod.getStatus().getContainerStatuses().get(0).getState().getTerminated() != null;
                }))
                .compose(ignore -> podOperator.getAsync(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster())))
                .compose(pod -> {
                    ContainerStateTerminated state = pod.getStatus().getContainerStatuses().get(0).getState().getTerminated();
                    if (state.getExitCode() == 0) {
                        buildState.desiredImage = state.getMessage().trim();
                        buildState.desiredBuildRevision = newBuildRevision;
                        log.info("Build completed successfully. New image is {}.", buildState.desiredImage);
                        return Future.succeededFuture();
                    } else {
                        log.info("Build failed with code {}: {}", state.getExitCode(), state.getMessage());
                        return Future.failedFuture("The Kafka Connect build failed");
                    }
                })
                .compose(i -> podOperator.reconcile(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), null))
                .mapEmpty();
    }

    /**
     * Executes the Kafka Connect Build on OpenShift. Run only if needed because of changes to the Dockerfile or when
     * triggered by annotation.
     *
     * @param namespace             Namespace where the Kafka Connect is deployed
     * @param connectBuild          The KafkaConnectBuild model with the build definitions
     * @param dockerfile            The generated Dockerfile
     * @param newBuildRevision      New build revision (hash of the Dockerfile)
     *
     * @return                      Future which completes when the build is finished (or fails if it fails)
     */
    private Future<Void> openShiftBuild(String namespace, KafkaConnectBuild connectBuild, BuildState buildState, KafkaConnectDockerfile dockerfile, String newBuildRevision)   {
        return configMapOperations.reconcile(namespace, KafkaConnectResources.dockerFileConfigMapName(connectBuild.getCluster()), null)
                .compose(ignore -> podOperator.reconcile(namespace, KafkaConnectResources.buildPodName(connectBuild.getCluster()), null))
                .compose(ignore -> buildConfigOperator.reconcile(namespace, KafkaConnectResources.buildConfigName(connectBuild.getCluster()), connectBuild.generateBuildConfig(dockerfile)))
                .compose(ignore -> buildConfigOperator.startBuild(namespace, KafkaConnectResources.buildConfigName(connectBuild.getCluster()), connectBuild.generateBuildRequest()))
                .compose(build -> {
                    buildState.currentBuildName = build.getMetadata().getName();
                    return buildOperator.waitFor(namespace, buildState.currentBuildName, "complete", 1_000, connectBuildTimeoutMs, (ignore1, ignore2) -> {
                        Build runningBuild = buildOperator.get(namespace, buildState.currentBuildName);

                        // Check if build is complete now (either with success or failure)
                        return runningBuild.getStatus() != null
                                && ("Complete".equals(runningBuild.getStatus().getPhase()) || "Failed".equals(runningBuild.getStatus().getPhase()) || "Error".equals(runningBuild.getStatus().getPhase()) || "Cancelled".equals(runningBuild.getStatus().getPhase()));
                    });
                })
                .compose(ignore -> buildOperator.getAsync(namespace, buildState.currentBuildName))
                .compose(build -> {
                    if (build.getStatus() != null
                            && "Complete".equals(build.getStatus().getPhase()))   {
                        // Build completed successfully. Lets extract the new image
                        if (build.getStatus().getOutputDockerImageReference() != null
                                && build.getStatus().getOutput() != null
                                && build.getStatus().getOutput().getTo() != null
                                && build.getStatus().getOutput().getTo().getImageDigest() != null) {
                            String digest = "@" + build.getStatus().getOutput().getTo().getImageDigest();
                            String image = build.getStatus().getOutputDockerImageReference();
                            String tag = image.substring(image.lastIndexOf(":"));

                            buildState.desiredImage = image.replace(tag, digest);
                            buildState.desiredBuildRevision = newBuildRevision;

                            log.info("Build {} completed successfully. New image is {}.", buildState.currentBuildName, buildState.desiredImage);
                            return Future.succeededFuture();
                        } else {
                            log.info("Build {} completed successfully. But the new container image was not found.", buildState.currentBuildName);
                            return Future.failedFuture("The Kafka Connect build " + buildState.currentBuildName + " completed, but the new container image was not found");
                        }
                    } else {
                        // Build failed. If the Status exists, we try to provide more detailed information
                        if (build.getStatus() != null) {
                            log.info("Build {} failed with code {}: {}", buildState.currentBuildName, build.getStatus().getPhase(), build.getStatus().getLogSnippet());
                        } else {
                            log.info("Build {} failed for unknown reason", buildState.currentBuildName);
                        }

                        return Future.failedFuture("The Kafka Connect build " + buildState.currentBuildName + " failed");
                    }
                })
                .mapEmpty();
    }

    /**
     * Utility class to held some helper states for the Kafka Connect Build. This helper class is used to pass the state
     * information around during the reconciliation. But also to make it easier to set the values from inside the lambdas.
     */
    static class BuildState    {
        public String currentImage;
        public String desiredImage;
        public String currentBuildRevision;
        public String desiredBuildRevision;
        public boolean forceRebuild = false;
        public String currentBuildName;
    }
}

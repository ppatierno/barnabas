package io.strimzi.controller.cluster.resources;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DoneableStatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.strimzi.controller.cluster.ClusterController;
import io.strimzi.controller.cluster.K8SUtils;
import io.strimzi.controller.cluster.operations.ResourceOperation;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ZookeeperCluster extends AbstractCluster {

    public static final String TYPE = "zookeeper";

    private final int clientPort = 2181;
    private final String clientPortName = "clients";
    private final int clusteringPort = 2888;
    private final String clusteringPortName = "clustering";
    private final int leaderElectionPort = 3888;
    private final String leaderElectionPortName = "leader-election";

    private static String NAME_SUFFIX = "-zookeeper";
    private static String HEADLESS_NAME_SUFFIX = NAME_SUFFIX + "-headless";
    private static String METRICS_CONFIG_SUFFIX = NAME_SUFFIX + "-metrics-config";

    // Zookeeper configuration
    // N/A

    // Configuration defaults
    private static String DEFAULT_IMAGE = "strimzi/zookeeper:latest";
    private static int DEFAULT_REPLICAS = 3;
    private static int DEFAULT_HEALTHCHECK_DELAY = 15;
    private static int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    private static boolean DEFAULT_ZOOKEEPER_METRICS_ENABLED = false;

    // Zookeeper configuration defaults
    // N/A

    // Configuration keys
    private static String KEY_IMAGE = "zookeeper-image";
    private static String KEY_REPLICAS = "zookeeper-nodes";
    private static String KEY_HEALTHCHECK_DELAY = "zookeeper-healthcheck-delay";
    private static String KEY_HEALTHCHECK_TIMEOUT = "zookeeper-healthcheck-timeout";
    private static String KEY_METRICS_CONFIG = "zookeeper-metrics-config";
    private static String KEY_STORAGE = "zookeeper-storage";

    // Zookeeper configuration keys
    private static String KEY_ZOOKEEPER_NODE_COUNT = "ZOOKEEPER_NODE_COUNT";
    private static String KEY_ZOOKEEPER_METRICS_ENABLED = "ZOOKEEPER_METRICS_ENABLED";

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Zookeeper cluster resources are going to be created
     * @param cluster   overall cluster name
     */
    private ZookeeperCluster(String namespace, String cluster) {

        super(namespace, cluster);
        this.name = cluster + ZookeeperCluster.NAME_SUFFIX;
        this.headlessName = cluster + ZookeeperCluster.HEADLESS_NAME_SUFFIX;
        this.metricsConfigName = cluster + ZookeeperCluster.METRICS_CONFIG_SUFFIX;
        this.image = DEFAULT_IMAGE;
        this.replicas = DEFAULT_REPLICAS;
        this.healthCheckPath = "/opt/zookeeper/zookeeper_healthcheck.sh";
        this.healthCheckTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.healthCheckInitialDelay = DEFAULT_HEALTHCHECK_DELAY;
        this.isMetricsEnabled = DEFAULT_ZOOKEEPER_METRICS_ENABLED;

        this.mounthPath = "/var/lib/zookeeper";
        this.volumeName = "zookeeper-storage";
        this.metricsConfigVolumeName = "zookeeper-metrics-config";
        this.metricsConfigMountPath = "/opt/prometheus/config/";
    }

    /**
     * Create a Zookeeper cluster from the related ConfigMap resource
     *
     * @param cm    ConfigMap with cluster configuration
     * @return  Zookeeper cluster instance
     */
    public static ZookeeperCluster fromConfigMap(ConfigMap cm) {

        ZookeeperCluster zk = new ZookeeperCluster(cm.getMetadata().getNamespace(), cm.getMetadata().getName());

        zk.setLabels(cm.getMetadata().getLabels());

        zk.setReplicas(Integer.parseInt(cm.getData().getOrDefault(KEY_REPLICAS, String.valueOf(DEFAULT_REPLICAS))));
        zk.setImage(cm.getData().getOrDefault(KEY_IMAGE, DEFAULT_IMAGE));
        zk.setHealthCheckInitialDelay(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_DELAY, String.valueOf(DEFAULT_HEALTHCHECK_DELAY))));
        zk.setHealthCheckTimeout(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_TIMEOUT, String.valueOf(DEFAULT_HEALTHCHECK_TIMEOUT))));

        String metricsConfig = cm.getData().get(KEY_METRICS_CONFIG);
        zk.setMetricsEnabled(metricsConfig != null);
        if (zk.isMetricsEnabled()) {
            zk.setMetricsConfig(new JsonObject(metricsConfig));
        }

        String storageConfig = cm.getData().get(KEY_STORAGE);
        zk.setStorage(Storage.fromJson(new JsonObject(storageConfig)));

        return zk;
    }

    /**
     * Create a Zookeeper cluster from the deployed StatefulSet resource
     *
     * @param namespace Kubernetes/OpenShift namespace where cluster resources belong to
     * @param cluster   overall cluster name
     * @return  Zookeeper cluster instance
     */
    public static ZookeeperCluster fromStatefulSet(ResourceOperation<KubernetesClient, StatefulSet, StatefulSetList, DoneableStatefulSet, RollableScalableResource<StatefulSet, DoneableStatefulSet>> statefulSetResources,
                                                   String namespace, String cluster) {

        StatefulSet ss = statefulSetResources.get(namespace, cluster + ZookeeperCluster.NAME_SUFFIX);

        ZookeeperCluster zk =  new ZookeeperCluster(namespace, cluster);

        zk.setLabels(ss.getMetadata().getLabels());
        zk.setReplicas(ss.getSpec().getReplicas());
        zk.setImage(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        zk.setHealthCheckInitialDelay(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds());
        zk.setHealthCheckInitialDelay(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds());

        Map<String, String> vars = ss.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().collect(
                Collectors.toMap(EnvVar::getName, EnvVar::getValue));

        zk.setMetricsEnabled(Boolean.parseBoolean(vars.getOrDefault(KEY_ZOOKEEPER_METRICS_ENABLED, String.valueOf(DEFAULT_ZOOKEEPER_METRICS_ENABLED))));
        if (zk.isMetricsEnabled()) {
            zk.setMetricsConfigName(cluster + ZookeeperCluster.METRICS_CONFIG_SUFFIX);
        }

        if (!ss.getSpec().getVolumeClaimTemplates().isEmpty()) {

            Storage storage = Storage.fromPersistentVolumeClaim(ss.getSpec().getVolumeClaimTemplates().get(0));
            if (ss.getMetadata().getAnnotations() != null) {
                String deleteClaimAnnotation = String.format("%s/%s", ClusterController.STRIMZI_CLUSTER_CONTROLLER_DOMAIN, Storage.DELETE_CLAIM_FIELD);
                storage.withDeleteClaim(Boolean.valueOf(ss.getMetadata().getAnnotations().computeIfAbsent(deleteClaimAnnotation, s -> "false")));
            }
            zk.setStorage(storage);
        } else {
            Storage storage = new Storage(Storage.StorageType.EPHEMERAL);
            zk.setStorage(storage);
        }

        return zk;
    }

    /**
     * Return the differences between the current Zookeeper cluster and the deployed one
     *
     * @param namespace Kubernetes/OpenShift namespace where cluster resources belong to
     * @return  ClusterDiffResult instance with differences
     */
    public ClusterDiffResult diff(ResourceOperation<KubernetesClient, ConfigMap, ConfigMapList, DoneableConfigMap, Resource<ConfigMap, DoneableConfigMap>> configMapResources,
                                  ResourceOperation<KubernetesClient, StatefulSet, StatefulSetList, DoneableStatefulSet, RollableScalableResource<StatefulSet, DoneableStatefulSet>> statefulSetResources,
                                  String namespace)  {
        StatefulSet ss = statefulSetResources.get(namespace, getName());
        ConfigMap metricsConfigMap = configMapResources.get(namespace, getMetricsConfigName());

        ClusterDiffResult diff = new ClusterDiffResult();

        if (replicas > ss.getSpec().getReplicas()) {
            log.info("Diff: Expected replicas {}, actual replicas {}", replicas, ss.getSpec().getReplicas());
            diff.setScaleUp(true);
            diff.setRollingUpdate(true);
        }
        else if (replicas < ss.getSpec().getReplicas()) {
            log.info("Diff: Expected replicas {}, actual replicas {}", replicas, ss.getSpec().getReplicas());
            diff.setScaleDown(true);
            diff.setRollingUpdate(true);
        }

        if (!getLabelsWithName().equals(ss.getMetadata().getLabels()))    {
            log.info("Diff: Expected labels {}, actual labels {}", getLabelsWithName(), ss.getMetadata().getLabels());
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        if (!image.equals(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())) {
            log.info("Diff: Expected image {}, actual image {}", image, ss.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        if (healthCheckInitialDelay != ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds()
                || healthCheckTimeout != ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds()) {
            log.info("Diff: Zookeeper healthcheck timing changed");
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        Map<String, String> vars = ss.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().collect(
                Collectors.toMap(EnvVar::getName, EnvVar::getValue));

        if (isMetricsEnabled != Boolean.parseBoolean(vars.getOrDefault(KEY_ZOOKEEPER_METRICS_ENABLED, String.valueOf(DEFAULT_ZOOKEEPER_METRICS_ENABLED)))) {
            log.info("Diff: Zookeeper metrics enabled/disabled");
            diff.setMetricsChanged(true);
            diff.setRollingUpdate(true);
        } else {

            if (isMetricsEnabled) {
                JsonObject metricsConfig = new JsonObject(metricsConfigMap.getData().get(METRICS_CONFIG_FILE));
                if (!this.metricsConfig.equals(metricsConfig)) {
                    diff.setMetricsChanged(true);
                }
            }
        }

        // get the current (deployed) kind of storage
        Storage ssStorage;
        if (ss.getSpec().getVolumeClaimTemplates().isEmpty()) {
            ssStorage = new Storage(Storage.StorageType.EPHEMERAL);
        } else {
            ssStorage = Storage.fromPersistentVolumeClaim(ss.getSpec().getVolumeClaimTemplates().get(0));
            // the delete-claim flack is backed by the StatefulSets
            if (ss.getMetadata().getAnnotations() != null) {
                String deleteClaimAnnotation = String.format("%s/%s", ClusterController.STRIMZI_CLUSTER_CONTROLLER_DOMAIN, Storage.DELETE_CLAIM_FIELD);
                ssStorage.withDeleteClaim(Boolean.valueOf(ss.getMetadata().getAnnotations().computeIfAbsent(deleteClaimAnnotation, s -> "false")));
            }
        }

        // compute the differences with the requested storage (from the updated ConfigMap)
        Storage.StorageDiffResult storageDiffResult = storage.diff(ssStorage);

        // check for all the not allowed changes to the storage
        boolean isStorageRejected = (storageDiffResult.isType() || storageDiffResult.isSize() ||
                storageDiffResult.isStorageClass() || storageDiffResult.isSelector());

        // only delete-claim flag can be changed
        if (!isStorageRejected && (storage.type() == Storage.StorageType.PERSISTENT_CLAIM)) {
            if (storageDiffResult.isDeleteClaim()) {
                diff.setDifferent(true);
            }
        } else if (isStorageRejected) {
            log.warn("Changing storage configuration other than delete-claim is not supported !");
        }

        return diff;
    }

    public Service generateService() {

        return createService("ClusterIP",
                Collections.singletonList(createServicePort(clientPortName, clientPort, clientPort, "TCP")));
    }

    public Service generateHeadlessService() {

        return createHeadlessService(headlessName, getServicePortList());
    }

    public Service patchHeadlessService(Service svc) {

        return patchHeadlessService(headlessName, svc);
    }

    public StatefulSet generateStatefulSet(boolean isOpenShift) {

        return createStatefulSet(
                getContainerPortList(),
                getVolumes(),
                getVolumeClaims(),
                getVolumeMounts(),
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                isOpenShift);
    }

    public ConfigMap generateMetricsConfigMap() {

        Map<String, String> data = new HashMap<>();
        data.put(METRICS_CONFIG_FILE, metricsConfig.toString());

        return createConfigMap(metricsConfigName, data);
    }

    public ConfigMap patchMetricsConfigMap(ConfigMap cm) {

        Map<String, String> data = new HashMap<>();
        data.put(METRICS_CONFIG_FILE, metricsConfig != null ? metricsConfig.toString() : null);

        return patchConfigMap(cm, data);
    }

    public StatefulSet patchStatefulSet(StatefulSet statefulSet) {

        Map<String, String> annotations = new HashMap<>();
        annotations.put(String.format("%s/%s", ClusterController.STRIMZI_CLUSTER_CONTROLLER_DOMAIN, Storage.DELETE_CLAIM_FIELD),
                String.valueOf(storage.isDeleteClaim()));

        return patchStatefulSet(statefulSet,
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                createExecProbe(healthCheckPath, healthCheckInitialDelay, healthCheckTimeout),
                annotations);
    }

    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(new EnvVarBuilder().withName(KEY_ZOOKEEPER_NODE_COUNT).withValue(Integer.toString(replicas)).build());
        varList.add(new EnvVarBuilder().withName(KEY_ZOOKEEPER_METRICS_ENABLED).withValue(String.valueOf(isMetricsEnabled)).build());

        return varList;
    }

    private List<ServicePort> getServicePortList() {
        List<ServicePort> portList = new ArrayList<>();
        portList.add(createServicePort(clientPortName, clientPort, clientPort, "TCP"));
        portList.add(createServicePort(clusteringPortName, clusteringPort, clusteringPort, "TCP"));
        portList.add(createServicePort(leaderElectionPortName, leaderElectionPort, leaderElectionPort, "TCP"));

        return portList;
    }

    private List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>();
        portList.add(createContainerPort(clientPortName, clientPort, "TCP"));
        portList.add(createContainerPort(clusteringPortName, clusteringPort, "TCP"));
        portList.add(createContainerPort(leaderElectionPortName, leaderElectionPort,"TCP"));
        if (isMetricsEnabled) {
            portList.add(createContainerPort(metricsPortName, metricsPort, "TCP"));
        }

        return portList;
    }

    private List<Volume> getVolumes() {
        List<Volume> volumeList = new ArrayList<>();
        if (storage.type() == Storage.StorageType.EPHEMERAL) {
            volumeList.add(createEmptyDirVolume(volumeName));
        }
        if (isMetricsEnabled) {
            volumeList.add(createConfigMapVolume(metricsConfigVolumeName, metricsConfigName));
        }

        return volumeList;
    }

    private List<PersistentVolumeClaim> getVolumeClaims() {
        List<PersistentVolumeClaim> pvcList = new ArrayList<>();
        if (storage.type() == Storage.StorageType.PERSISTENT_CLAIM) {
            pvcList.add(createPersistentVolumeClaim(volumeName));
        }
        return pvcList;
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>();
        volumeMountList.add(createVolumeMount(volumeName, mounthPath));
        if (isMetricsEnabled) {
            volumeMountList.add(createVolumeMount(metricsConfigVolumeName, metricsConfigMountPath));
        }

        return volumeMountList;
    }

    protected void setLabels(Map<String, String> labels) {
        Map<String, String> newLabels = new HashMap<>(labels);

        if (newLabels.containsKey(ClusterController.STRIMZI_TYPE_LABEL) &&
                newLabels.get(ClusterController.STRIMZI_TYPE_LABEL).equals(KafkaCluster.TYPE)) {
            newLabels.put(ClusterController.STRIMZI_TYPE_LABEL, ZookeeperCluster.TYPE);
        }

        super.setLabels(newLabels);
    }
}

package io.strimzi.controller.cluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.strimzi.controller.cluster.operations.KafkaClusterOperation;
import io.strimzi.controller.cluster.operations.KafkaConnectClusterOperation;
import io.strimzi.controller.cluster.operations.ZookeeperClusterOperation;
import io.strimzi.controller.cluster.operations.resource.BuildConfigResources;
import io.strimzi.controller.cluster.operations.resource.ConfigMapResources;
import io.strimzi.controller.cluster.operations.resource.DeploymentResources;
import io.strimzi.controller.cluster.operations.resource.ImageStreamResources;
import io.strimzi.controller.cluster.operations.resource.PvcResources;
import io.strimzi.controller.cluster.operations.resource.ServiceResources;
import io.strimzi.controller.cluster.operations.resource.StatefulSetResources;
import io.strimzi.controller.cluster.resources.KafkaCluster;
import io.strimzi.controller.cluster.resources.KafkaConnectCluster;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.vertx.core.*;
import io.vertx.core.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ClusterController extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ClusterController.class.getName());

    public static final String STRIMZI_DOMAIN = "strimzi.io";
    public static final String STRIMZI_CLUSTER_CONTROLLER_DOMAIN = "cluster.controller.strimzi.io";
    public static final String STRIMZI_TYPE_LABEL = STRIMZI_DOMAIN + "/type";
    public static final String STRIMZI_CLUSTER_LABEL = STRIMZI_DOMAIN + "/cluster";
    public static final String STRIMZI_NAME_LABEL = STRIMZI_DOMAIN + "/name";

    private static final int HEALTH_SERVER_PORT = 8080;

    private final KubernetesClient client;
    private final Map<String, String> labels;
    private final String namespace;
    private final ConfigMapResources configMapResources;
    private final StatefulSetResources statefulSetResources;
    private final DeploymentResources deploymentResources;

    private Watch configMapWatch;

    private long reconcileTimer;
    private final ZookeeperClusterOperation zookeeperClusterOperation;
    private final KafkaClusterOperation kafkaClusterOperation;
    private final KafkaConnectClusterOperation kafkaConnectClusterOperation;

    public ClusterController(ClusterControllerConfig config) {
        log.info("Creating ClusterController");

        this.namespace = config.getNamespace();
        this.labels = config.getLabels();
        this.client = new DefaultKubernetesClient();
        ServiceResources serviceResources = new ServiceResources(vertx, client);
        statefulSetResources = new StatefulSetResources(vertx, client);
        configMapResources = new ConfigMapResources(vertx, client);
        PvcResources pvcResources = new PvcResources(vertx, client);
        deploymentResources = new DeploymentResources(vertx, client);
        ImageStreamResources imagesStreamResources;
        BuildConfigResources buildConfigResources;
        if (client.isAdaptable(OpenShiftClient.class)) {
            imagesStreamResources = new ImageStreamResources(vertx, client.adapt(OpenShiftClient.class));
            buildConfigResources = new BuildConfigResources(vertx, client.adapt(OpenShiftClient.class));
        } else {
            imagesStreamResources = null;
            buildConfigResources = null;
        }
        this.zookeeperClusterOperation = new ZookeeperClusterOperation(vertx, client, serviceResources, statefulSetResources, configMapResources, pvcResources);
        this.kafkaClusterOperation = new KafkaClusterOperation(vertx, client, configMapResources, statefulSetResources, serviceResources, pvcResources);
        this.kafkaConnectClusterOperation = new KafkaConnectClusterOperation(vertx, client, serviceResources, deploymentResources, configMapResources, imagesStreamResources, buildConfigResources);
    }

    @Override
    public void start(Future<Void> start) {
        log.info("Starting ClusterController");

        // Configure the executor here, but it is used only in other places
        getVertx().createSharedWorkerExecutor("kubernetes-ops-pool", 5, TimeUnit.SECONDS.toNanos(120));

        createConfigMapWatch(res -> {
            if (res.succeeded())    {
                configMapWatch = res.result();

                log.info("Setting up periodical reconciliation");
                this.reconcileTimer = vertx.setPeriodic(120000, res2 -> {
                    log.info("Triggering periodic reconciliation ...");
                    reconcile();
                });

                log.info("ClusterController up and running");

                // start the HTTP server for healthchecks
                this.startHealthServer();

                start.complete();
            }
            else {
                log.error("ClusterController startup failed");
                start.fail("ClusterController startup failed");
            }
        });
    }

    @Override
    public void stop(Future<Void> stop) throws Exception {

        vertx.cancelTimer(reconcileTimer);
        configMapWatch.close();
        client.close();

        stop.complete();
    }

    private void createConfigMapWatch(Handler<AsyncResult<Watch>> handler) {
        getVertx().executeBlocking(
                future -> {
                    Watch watch = client.configMaps().inNamespace(namespace).withLabels(labels).watch(new Watcher<ConfigMap>() {
                        @Override
                        public void eventReceived(Action action, ConfigMap cm) {
                            Map<String, String> labels = cm.getMetadata().getLabels();
                            String type;

                            if (!labels.containsKey(ClusterController.STRIMZI_TYPE_LABEL)) {
                                log.warn("Missing type in Config Map {}", cm.getMetadata().getName());
                                return;
                            }
                            else if (!labels.get(ClusterController.STRIMZI_TYPE_LABEL).equals(KafkaCluster.TYPE) &&
                                     !labels.get(ClusterController.STRIMZI_TYPE_LABEL).equals(KafkaConnectCluster.TYPE)) {
                                log.warn("Unknown type {} received in Config Map {}", labels.get(ClusterController.STRIMZI_TYPE_LABEL), cm.getMetadata().getName());
                                return;
                            }
                            else {
                                type = labels.get(ClusterController.STRIMZI_TYPE_LABEL);
                            }

                            switch (action) {
                                case ADDED:
                                    log.info("New ConfigMap {}", cm.getMetadata().getName());
                                    if (type.equals(KafkaCluster.TYPE)) {
                                        addKafkaCluster(cm);
                                    }
                                    else if (type.equals(KafkaConnectCluster.TYPE)) {
                                        addKafkaConnectCluster(cm);
                                    }
                                    break;
                                case DELETED:
                                    log.info("Deleted ConfigMap {}", cm.getMetadata().getName());
                                    if (type.equals(KafkaCluster.TYPE)) {
                                        deleteKafkaCluster(cm);
                                    }
                                    else if (type.equals(KafkaConnectCluster.TYPE)) {
                                        deleteKafkaConnectCluster(cm);
                                    }
                                    break;
                                case MODIFIED:
                                    log.info("Modified ConfigMap {}", cm.getMetadata().getName());
                                    if (type.equals(KafkaCluster.TYPE)) {
                                        updateKafkaCluster(cm);
                                    }
                                    else if (type.equals(KafkaConnectCluster.TYPE)) {
                                        updateKafkaConnectCluster(cm);
                                    }
                                    break;
                                case ERROR:
                                    log.error("Failed ConfigMap {}", cm.getMetadata().getName());
                                    reconcile();
                                    break;
                                default:
                                    log.error("Unknown action: {}", cm.getMetadata().getName());
                                    reconcile();
                            }
                        }

                        @Override
                        public void onClose(KubernetesClientException e) {
                            if (e != null) {
                                log.error("Watcher closed with exception", e);
                            }
                            else {
                                log.error("Watcher closed with exception", e);
                            }

                            recreateConfigMapWatch();
                        }
                    });
                    future.complete(watch);
                }, res -> {
                    if (res.succeeded())    {
                        log.info("ConfigMap watcher up and running for labels {}", labels);
                        handler.handle(Future.succeededFuture((Watch)res.result()));
                    }
                    else {
                        log.info("ConfigMap watcher failed to start");
                        handler.handle(Future.failedFuture("ConfigMap watcher failed to start"));
                    }
                }
        );
    }

    private void recreateConfigMapWatch() {
        configMapWatch.close();

        createConfigMapWatch(res -> {
            if (res.succeeded())    {
                log.info("ConfigMap watch recreated");
                configMapWatch = res.result();
            }
            else {
                log.error("Failed to recreate ConfigMap watch");
            }
        });
    }

    /*
      Periodical reconciliation (in case we lost some event)
     */
    private void reconcile() {
        reconcileKafka();
        reconcileKafkaConnect();
    }

    private void reconcileKafka() {
        log.info("Reconciling Kafka clusters ...");

        Map<String, String> kafkaLabels = new HashMap(labels);
        kafkaLabels.put(ClusterController.STRIMZI_TYPE_LABEL, KafkaCluster.TYPE);

        List<ConfigMap> cms = configMapResources.list(namespace, kafkaLabels);
        List<StatefulSet> sss = statefulSetResources.list(namespace, kafkaLabels);

        List<String> cmsNames = cms.stream().map(cm -> cm.getMetadata().getName()).collect(Collectors.toList());
        List<String> sssNames = sss.stream().map(cm -> cm.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL)).collect(Collectors.toList());

        List<ConfigMap> addList = cms.stream().filter(cm -> !sssNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<ConfigMap> updateList = cms.stream().filter(cm -> sssNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<StatefulSet> deletionList = sss.stream().filter(ss -> !cmsNames.contains(ss.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL))).collect(Collectors.toList());

        addKafkaClusters(addList);
        deleteKafkaClusters(deletionList);
        updateKafkaClusters(updateList);
    }

    private void addKafkaClusters(List<ConfigMap> add)   {
        for (ConfigMap cm : add) {
            log.info("Reconciliation: Kafka cluster {} should be added", cm.getMetadata().getName());
            addKafkaCluster(cm);
        }
    }

    private void updateKafkaClusters(List<ConfigMap> update)   {
        for (ConfigMap cm : update) {
            log.info("Reconciliation: Kafka cluster {} should be checked for updates", cm.getMetadata().getName());
            updateKafkaCluster(cm);
        }
    }

    private void deleteKafkaClusters(List<StatefulSet> delete)   {
        for (StatefulSet ss : delete) {
            log.info("Reconciliation: Kafka cluster {} should be deleted", ss.getMetadata().getName());
            deleteKafkaCluster(ss);
        }
    }

    private void reconcileKafkaConnect() {
        log.info("Reconciling Kafka Connect clusters ...");

        Map<String, String> kafkaLabels = new HashMap(labels);
        kafkaLabels.put(ClusterController.STRIMZI_TYPE_LABEL, KafkaConnectCluster.TYPE);

        List<ConfigMap> cms = configMapResources.list(namespace, kafkaLabels);
        List<Deployment> deps = deploymentResources.list(namespace, kafkaLabels);

        List<String> cmsNames = cms.stream().map(cm -> cm.getMetadata().getName()).collect(Collectors.toList());
        List<String> depsNames = deps.stream().map(cm -> cm.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL)).collect(Collectors.toList());

        List<ConfigMap> addList = cms.stream().filter(cm -> !depsNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<ConfigMap> updateList = cms.stream().filter(cm -> depsNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<Deployment> deletionList = deps.stream().filter(dep -> !cmsNames.contains(dep.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL))).collect(Collectors.toList());

        addKafkaConnectClusters(addList);
        deleteConnectConnectClusters(deletionList);
        updateKafkaConnectClusters(updateList);
    }

    private void addKafkaConnectClusters(List<ConfigMap> add)   {
        for (ConfigMap cm : add) {
            log.info("Reconciliation: Kafka Connect cluster {} should be added", cm.getMetadata().getName());
            addKafkaConnectCluster(cm);
        }
    }

    private void updateKafkaConnectClusters(List<ConfigMap> update)   {
        for (ConfigMap cm : update) {
            log.info("Reconciliation: Kafka Connect cluster {} should be checked for updates", cm.getMetadata().getName());
            updateKafkaConnectCluster(cm);
        }
    }

    private void deleteConnectConnectClusters(List<Deployment> delete)   {
        for (Deployment dep : delete) {
            log.info("Reconciliation: Kafka Connect cluster {} should be deleted", dep.getMetadata().getName());
            deleteKafkaConnectCluster(dep);
        }
    }

    /*
      Kafka / Zookeeper cluster control
     */
    private void addKafkaCluster(ConfigMap add)   {
        String name = add.getMetadata().getName();
        log.info("Adding cluster {}", name);

        getZookeeperClusterOperation().create(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Zookeeper cluster added {}", name);
                getKafkaClusterOperation().create(namespace, name, res2 -> {
                    if (res2.succeeded()) {
                        log.info("Kafka cluster added {}", name);
                    }
                    else {
                        log.error("Failed to add Kafka cluster {}.", name);
                    }
                });
            }
            else {
                log.error("Failed to add Zookeeper cluster {}. SKipping Kafka cluster creation.", name);
            }
        });
    }

    private void updateKafkaCluster(ConfigMap cm)   {
        String name = cm.getMetadata().getName();
        log.info("Checking for updates in cluster {}", cm.getMetadata().getName());

        getZookeeperClusterOperation().update(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Zookeeper cluster updated {}", name);
            }
            else {
                log.error("Failed to update Zookeeper cluster {}.", name);
            }

            getKafkaClusterOperation().update(namespace, name, res2 -> {
                if (res2.succeeded()) {
                    log.info("Kafka cluster updated {}", name);
                }
                else {
                    log.error("Failed to update Kafka cluster {}.", name);
                }
            });
        });
    }

    private void deleteKafkaCluster(StatefulSet ss)   {
        String name = ss.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL);
        log.info("Deleting cluster {}", name);
        deleteKafkaCluster(namespace, name);
    }

    private void deleteKafkaCluster(ConfigMap cm)   {
        String name = cm.getMetadata().getName();
        log.info("Deleting cluster {}", name);
        deleteKafkaCluster(namespace, name);
    }

    private void deleteKafkaCluster(String namespace, String name)   {
        getKafkaClusterOperation().delete(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Kafka cluster deleted {}", name);
                getZookeeperClusterOperation().delete(namespace, name, res2 -> {
                    if (res2.succeeded()) {
                        log.info("Zookeeper cluster deleted {}", name);
                    }
                    else {
                        log.error("Failed to delete Zookeeper cluster {}.", name);
                    }
                });
            }
            else {
                log.error("Failed to delete Kafka cluster {}. Skipping Zookeeper cluster deletion.", name);
            }
        });
    }

    /*
      Kafka Connect cluster control
     */
    private void addKafkaConnectCluster(ConfigMap add)   {
        String name = add.getMetadata().getName();
        log.info("Adding Kafka Connect cluster {}", name);

        getKafkaConnectClusterOperation().create(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Kafka Connect cluster added {}", name);
            }
            else {
                log.error("Failed to add Kafka Connect cluster {}.", name);
            }
        });
    }

    private void updateKafkaConnectCluster(ConfigMap cm)   {
        String name = cm.getMetadata().getName();
        log.info("Checking for updates in Kafka Connect cluster {}", cm.getMetadata().getName());

        getKafkaConnectClusterOperation().update(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Kafka Connect cluster updated {}", name);
            }
            else {
                log.error("Failed to update Kafka Connect cluster {}.", name);
            }
        });
    }

    private void deleteKafkaConnectCluster(Deployment dep)   {
        String name = dep.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL);
        log.info("Deleting cluster {}", name);
        deleteKafkaConnectCluster(namespace, name);
    }

    private void deleteKafkaConnectCluster(ConfigMap cm)   {
        String name = cm.getMetadata().getName();
        log.info("Deleting cluster {}", name);
        deleteKafkaConnectCluster(namespace, name);
    }

    private void deleteKafkaConnectCluster(String namespace, String name)   {
        getKafkaConnectClusterOperation().delete(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("Kafka Connect cluster deleted {}", name);
            }
            else {
                log.error("Failed to delete Kafka Connect cluster {}.", name);
            }
        });
    }

    /**
     * Start an HTTP health server
     */
    private void startHealthServer() {

        this.vertx.createHttpServer()
                .requestHandler(request -> {

                    if (request.path().equals("/healthy")) {
                        request.response().setStatusCode(HttpResponseStatus.OK.code()).end();
                    } else if (request.path().equals("/ready")) {
                        request.response().setStatusCode(HttpResponseStatus.OK.code()).end();
                    }
                })
                .listen(HEALTH_SERVER_PORT);
    }

    private ZookeeperClusterOperation getZookeeperClusterOperation() {
        return zookeeperClusterOperation;
    }

    private KafkaClusterOperation getKafkaClusterOperation() {
        return kafkaClusterOperation;
    }

    private KafkaConnectClusterOperation getKafkaConnectClusterOperation() {
        return kafkaConnectClusterOperation;
    }
}

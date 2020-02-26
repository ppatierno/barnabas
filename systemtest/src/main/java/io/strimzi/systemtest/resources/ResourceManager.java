/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.resources;

import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.utils.kubeUtils.controllers.ConfigMapUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.ReplicaSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PersistentVolumeClaimUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.k8s.cmdClient.KubeCmdClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static io.strimzi.systemtest.Constants.CLUSTER_ROLE_BINDING;
import static io.strimzi.systemtest.Constants.DEPLOYMENT;
import static io.strimzi.systemtest.Constants.INGRESS;
import static io.strimzi.systemtest.Constants.ROLE_BINDING;
import static io.strimzi.systemtest.Constants.SERVICE;

public class ResourceManager {

    private static final Logger LOGGER = LogManager.getLogger(ResourceManager.class);

    public static final String STRIMZI_PATH_TO_CO_CONFIG = "../install/cluster-operator/050-Deployment-strimzi-cluster-operator.yaml";

    private static Stack<Runnable> classResources = new Stack<>();
    private static Stack<Runnable> methodResources = new Stack<>();
    private static Stack<Runnable> pointerResources = classResources;

    private static ResourceManager instance;

    public static synchronized ResourceManager getInstance() {
        if (instance == null) {
            instance = new ResourceManager();
        }
        return instance;
    }

    private ResourceManager() {}

    public static KubeClient kubeClient() {
        return KubeClusterResource.kubeClient();
    }

    public static KubeCmdClient cmdKubeClient() {
        return KubeClusterResource.cmdKubeClient();
    }

    public static Stack<Runnable> getPointerResources() {
        return pointerResources;
    }

    public static void setMethodResources() {
        LOGGER.info("Setting pointer to method resources");
        pointerResources = methodResources;
    }

    public static void setClassResources() {
        LOGGER.info("Setting pointer to class resources");
        pointerResources = classResources;
    }

    public static <T extends CustomResource, L extends CustomResourceList<T>, D extends Doneable<T>> void replaceCrdResource(Class<T> crdClass, Class<L> listClass, Class<D> doneableClass, String resourceName, Consumer<T> editor) {
        Resource<T, D> namedResource = Crds.operation(kubeClient().getClient(), crdClass, listClass, doneableClass).inNamespace(kubeClient().getNamespace()).withName(resourceName);
        T resource = namedResource.get();
        editor.accept(resource);
        namedResource.replace(resource);
    }
    @SuppressWarnings("unchecked")
    public static <T extends HasMetadata> T deleteLater(MixedOperation<T, ?, ?, ?> operation, T resource) {
        LOGGER.info("Scheduled deletion of {} {} in namespace {}",
                resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace() == null ? "(not set)" : resource.getMetadata().getNamespace());
        switch (resource.getKind()) {
            case Kafka.RESOURCE_KIND:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    waitForDeletion((Kafka) resource);
                });
                break;
            case KafkaConnect.RESOURCE_KIND:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    waitForDeletion((KafkaConnect) resource);
                });
                break;
            case KafkaConnectS2I.RESOURCE_KIND:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    waitForDeletion((KafkaConnectS2I) resource);
                });
                break;
            case KafkaMirrorMaker.RESOURCE_KIND:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    waitForDeletion((KafkaMirrorMaker) resource);
                });
                break;
            case KafkaBridge.RESOURCE_KIND:
                pointerResources.add(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    waitForDeletion((KafkaBridge) resource);
                });
                break;
            case DEPLOYMENT:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {}",
                            resource.getKind(), resource.getMetadata().getName());
                    waitForDeletion((Deployment) resource);
                });
                break;
            case CLUSTER_ROLE_BINDING:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {}",
                            resource.getKind(), resource.getMetadata().getName());
                    kubeClient().getClient().rbac().clusterRoleBindings().withName(resource.getMetadata().getName()).delete();
                });
                break;
            case ROLE_BINDING:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {}",
                            resource.getKind(), resource.getMetadata().getName());
                    kubeClient().getClient().rbac().roleBindings().inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName()).delete();
                });
                break;
            case SERVICE:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    kubeClient().getClient().services().inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName()).delete();
                });
                break;
            case INGRESS:
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                    kubeClient().deleteIngress((Ingress) resource);
                });
                break;
            default :
                pointerResources.push(() -> {
                    LOGGER.info("Deleting {} {} in namespace {}",
                            resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace());
                    operation.inNamespace(resource.getMetadata().getNamespace())
                            .withName(resource.getMetadata().getName())
                            .cascading(true)
                            .delete();
                });
        }
        return resource;
    }

    private static void waitForDeletion(Kafka kafka) {
        String kafkaClusterName = kafka.getMetadata().getName();
        LOGGER.info("Waiting when all the pods are terminated for Kafka {}", kafkaClusterName);

        StatefulSetUtils.waitForStatefulSetDeletion(KafkaResources.zookeeperStatefulSetName(kafkaClusterName));

        IntStream.rangeClosed(0, kafka.getSpec().getZookeeper().getReplicas() - 1).forEach(podIndex ->
                PodUtils.waitForPodDeletion(kafka.getMetadata().getName() + "-zookeeper-" + podIndex));

        StatefulSetUtils.waitForStatefulSetDeletion(KafkaResources.kafkaStatefulSetName(kafkaClusterName));

        IntStream.rangeClosed(0, kafka.getSpec().getKafka().getReplicas() - 1).forEach(podIndex ->
                PodUtils.waitForPodDeletion(kafka.getMetadata().getName() + "-kafka-" + podIndex));

        // Wait for EO deletion
        DeploymentUtils.waitForDeploymentDeletion(KafkaResources.entityOperatorDeploymentName(kafkaClusterName));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaResources.entityOperatorDeploymentName(kafkaClusterName));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().contains(kafka.getMetadata().getName() + "-entity-operator"))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));

        // Wait for Kafka Exporter deletion
        DeploymentUtils.waitForDeploymentDeletion(KafkaExporterResources.deploymentName(kafkaClusterName));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaExporterResources.deploymentName(kafkaClusterName));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().contains(kafka.getMetadata().getName() + "-kafka-exporter"))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));

        SecretUtils.waitForClusterSecretsDeletion(kafkaClusterName);
        PersistentVolumeClaimUtils.waitUntilPVCDeletion(kafkaClusterName);

        ConfigMapUtils.waitUntilConfigMapDeletion(KafkaResources.kafkaMetricsAndLogConfigMapName(kafka.getMetadata().getName()));
        ConfigMapUtils.waitUntilConfigMapDeletion(KafkaResources.zookeeperMetricsAndLogConfigMapName(kafka.getMetadata().getName()));
    }

    private static void waitForDeletion(KafkaConnect kafkaConnect) {
        LOGGER.info("Waiting when all the pods are terminated for Kafka Connect {}", kafkaConnect.getMetadata().getName());

        DeploymentUtils.waitForDeploymentDeletion(KafkaMirrorMakerResources.deploymentName(kafkaConnect.getMetadata().getName()));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaMirrorMakerResources.deploymentName(kafkaConnect.getMetadata().getName()));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().startsWith(kafkaConnect.getMetadata().getName() + "-connect-"))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));
    }

    private static void waitForDeletion(KafkaConnectS2I kafkaConnectS2I) {
        LOGGER.info("Waiting when all the pods are terminated for Kafka Connect S2I {}", kafkaConnectS2I.getMetadata().getName());

        DeploymentUtils.waitForDeploymentConfigDeletion(KafkaMirrorMakerResources.deploymentName(kafkaConnectS2I.getMetadata().getName()));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaMirrorMakerResources.deploymentName(kafkaConnectS2I.getMetadata().getName()));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().contains("-connect-"))
                .forEach(p -> {
                    LOGGER.debug("Deleting: {}", p.getMetadata().getName());
                    kubeClient().deletePod(p);
                });
    }

    private static void waitForDeletion(KafkaMirrorMaker kafkaMirrorMaker) {
        LOGGER.info("Waiting when all the pods are terminated for Kafka Mirror Maker {}", kafkaMirrorMaker.getMetadata().getName());

        DeploymentUtils.waitForDeploymentDeletion(KafkaMirrorMakerResources.deploymentName(kafkaMirrorMaker.getMetadata().getName()));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaMirrorMakerResources.deploymentName(kafkaMirrorMaker.getMetadata().getName()));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().startsWith(kafkaMirrorMaker.getMetadata().getName() + "-mirror-maker-"))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));
    }

    private static void waitForDeletion(KafkaBridge kafkaBridge) {
        LOGGER.info("Waiting when all the pods are terminated for Kafka Bridge {}", kafkaBridge.getMetadata().getName());

        DeploymentUtils.waitForDeploymentDeletion(KafkaMirrorMakerResources.deploymentName(kafkaBridge.getMetadata().getName()));
        ReplicaSetUtils.waitForReplicaSetDeletion(KafkaMirrorMakerResources.deploymentName(kafkaBridge.getMetadata().getName()));

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().startsWith(kafkaBridge.getMetadata().getName() + "-bridge-"))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));
    }

    private static void waitForDeletion(Deployment deployment) {
        LOGGER.info("Waiting when all the pods are terminated for Deployment {}", deployment.getMetadata().getName());

        DeploymentUtils.waitForDeploymentDeletion(deployment.getMetadata().getName());

        kubeClient().listPods().stream()
                .filter(p -> p.getMetadata().getName().startsWith(deployment.getMetadata().getName()))
                .forEach(p -> PodUtils.waitForPodDeletion(p.getMetadata().getName()));
    }

    public static void deleteClassResources() {
        LOGGER.info("Going to clear all class resources");
        while (!classResources.empty()) {
            classResources.pop().run();
        }
        classResources.clear();
    }

    public static void deleteMethodResources() {
        LOGGER.info("Going to clear all method resources");
        while (!methodResources.empty()) {
            methodResources.pop().run();
        }
        methodResources.clear();
        pointerResources = classResources;
    }

    public static String getImageValueFromCO(String name) {
        Deployment clusterOperator = getDeploymentFromYaml(STRIMZI_PATH_TO_CO_CONFIG);

        List<EnvVar> listEnvVar = clusterOperator.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        Optional<EnvVar> envVar = listEnvVar.stream().filter(e -> e.getName().equals(name)).findFirst();
        if (envVar.isPresent()) {
            return envVar.get().getValue();
        }
        return "";
    }

    private static Deployment getDeploymentFromYaml(String yamlPath) {
        return TestUtils.configFromYaml(yamlPath, Deployment.class);
    }
}

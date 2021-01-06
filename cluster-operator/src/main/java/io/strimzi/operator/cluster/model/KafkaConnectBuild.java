/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildOutput;
import io.fabric8.openshift.api.model.BuildOutputBuilder;
import io.fabric8.openshift.api.model.BuildTriggerPolicyBuilder;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectSpec;
import io.strimzi.api.kafka.model.connect.build.Build;
import io.strimzi.api.kafka.model.connect.build.DockerOutput;
import io.strimzi.api.kafka.model.connect.build.ImageStreamOutput;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.template.KafkaConnectTemplate;
import io.strimzi.operator.cluster.ClusterOperatorConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KafkaConnectBuild extends AbstractModel {
    protected static final String APPLICATION_NAME = "kafka-connect-build";

    private static final String DEFAULT_KANIKO_EXECUTOR_IMAGE = "gcr.io/kaniko-project/executor:latest";

    private Build build;
    private List<ContainerEnvVar> templateBuildContainerEnvVars;
    private SecurityContext templateBuildContainerSecurityContext;
    private Map<String, String> templateBuildConfigLabels;
    private Map<String, String> templateBuildConfigAnnotations;
    private String baseImage;

    /**
     * Constructor
     *
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    protected KafkaConnectBuild(HasMetadata resource) {
        super(resource, APPLICATION_NAME);
        this.name = KafkaConnectResources.buildPodName(cluster);
        this.image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KANIKO_EXECUTOR_IMAGE, DEFAULT_KANIKO_EXECUTOR_IMAGE);
    }

    /**
     * Created the KafkaConnectBuild instance from the Kafka Connect Custom Resource
     *
     * @param kafkaConnect  Kafka Connect CR with the build configuration
     * @param versions      Kafka versions configuration
     * @return              Instance of KafkaConnectBuild class
     */
    public static KafkaConnectBuild fromCrd(KafkaConnect kafkaConnect, KafkaVersion.Lookup versions) {
        KafkaConnectBuild build = new KafkaConnectBuild(kafkaConnect);
        KafkaConnectSpec spec = kafkaConnect.getSpec();

        if (spec != null) {
            build.setOwnerReference(kafkaConnect);

            if (spec.getBuild() != null)    {
                validateBuildConfiguration(spec.getBuild());
            }

            if (spec.getImage() == null) {
                build.baseImage = versions.kafkaConnectVersion(spec.getImage(), spec.getVersion());
            }

            if (spec.getTemplate() != null) {
                KafkaConnectTemplate template = spec.getTemplate();

                ModelUtils.parsePodTemplate(build, template.getBuildPod());

                if (template.getBuildContainer() != null && template.getBuildContainer().getEnv() != null) {
                    build.templateBuildContainerEnvVars = template.getBuildContainer().getEnv();
                }

                if (template.getBuildContainer() != null && template.getBuildContainer().getSecurityContext() != null) {
                    build.templateBuildContainerSecurityContext = template.getBuildContainer().getSecurityContext();
                }

                if (template.getBuildConfig() != null)  {
                    if (template.getBuildConfig().getLabels() != null)  {
                        build.templateBuildConfigLabels = template.getBuildConfig().getLabels();
                    }

                    if (template.getBuildConfig().getAnnotations() != null)  {
                        build.templateBuildConfigAnnotations = template.getBuildConfig().getAnnotations();
                    }
                }
            }

            build.build = spec.getBuild();

            return build;
        } else {
            throw new InvalidResourceException("Required .spec section is missing.");
        }
    }

    /**
     * Validates the Build configuration to check unique connector names etc.
     *
     * @param build     Kafka Connect Build configuration
     */
    private static void validateBuildConfiguration(Build build)    {
        if (build.getPlugins() != null) {
            List<String> names = build.getPlugins().stream().map(Plugin::getName).distinct().collect(Collectors.toList());

            if (names.size() != build.getPlugins().size())  {
                throw new InvalidResourceException("Connector plugins names have to be unique within a single KafkaConnect resource.");
            }

            for (Plugin plugin : build.getPlugins())    {
                if (plugin.getArtifacts() == null)  {
                    throw new InvalidResourceException("Each connector plugin needs to have a list of artifacts.");
                }
            }
        } else {
            throw new InvalidResourceException("List of connector plugins is required when Kafka Connect Build is used.");
        }
    }

    /**
     * Returns the build configuration of the KafkaConnect CR
     *
     * @return  Kafka Connect build configuration
     */
    public Build getBuild() {
        return build;
    }

    /**
     * Generates a ConfigMap with the Dockerfile used for the build
     *
     * @param dockerfile    Instance of the KafkaConnectDockerfile class with the prepared Dockerfile
     *
     * @return  ConfigMap with the Dockerfile
     */
    public ConfigMap generateDockerfileConfigMap(KafkaConnectDockerfile dockerfile)   {
        return createConfigMap(
                KafkaConnectResources.dockerFileConfigMapName(cluster),
                Collections.singletonMap("Dockerfile", dockerfile.getDockerfile())
        );
    }

    /**
     * Generates the Dockerfile based on the Kafka Connect build configuration.
     *
     * @return  Instance of the KafkaConnectDockerfile class with the prepared Dockerfile
     */
    public KafkaConnectDockerfile generateDockerfile()  {
        return new KafkaConnectDockerfile(baseImage, build);
    }

    /**
     * Generates builder Pod for building a new KafkaConnect container image with additional connector plugins
     *
     * @param isOpenShift       Flag defining whether we are running on OpenShift
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  Image pull secrets
     *
     * @return  Pod which will build the new container image
     */
    public Pod generateBuilderPod(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        return createPod(
                KafkaConnectResources.buildPodName(cluster),
                Collections.emptyMap(),
                getVolumes(isOpenShift),
                null,
                getContainers(imagePullPolicy),
                imagePullSecrets,
                isOpenShift
        );
    }

    /**
     * Generates a list of volumes used by the builder pod
     *
     * @param isOpenShift   Flag defining whether we are running on OpenShift
     *
     * @return  List of volumes
     */
    private List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumes = new ArrayList<>(3);

        volumes.add(VolumeUtils.createEmptyDirVolume("workspace", null));
        volumes.add(VolumeUtils.createConfigMapVolume("dockerfile", KafkaConnectResources.dockerFileConfigMapName(cluster), Collections.singletonMap("Dockerfile", "Dockerfile")));

        if (build.getOutput() instanceof DockerOutput) {
            DockerOutput output = (DockerOutput) build.getOutput();

            if (output.getPushSecret() != null) {
                volumes.add(VolumeUtils.createSecretVolume("docker-credentials", output.getPushSecret(), Collections.singletonMap(".dockerconfigjson", "config.json"), isOpenShift));
            }
        } else {
            throw new RuntimeException("Kubernetes build requires output of type `docker`.");
        }

        return volumes;
    }

    /**
     * Generates a list of volume mounts used by the builder pod
     *
     * @return  List of volume mounts
     */
    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMounts = new ArrayList<>(3);

        volumeMounts.add(new VolumeMountBuilder().withName("workspace").withMountPath("/workspace").build());
        volumeMounts.add(new VolumeMountBuilder().withName("dockerfile").withMountPath("/dockerfile").build());

        if (build.getOutput() instanceof DockerOutput) {
            DockerOutput output = (DockerOutput) build.getOutput();

            if (output.getPushSecret() != null) {
                volumeMounts.add(new VolumeMountBuilder().withName("docker-credentials").withMountPath("/kaniko/.docker").build());
            }
        } else {
            throw new RuntimeException("Kubernetes build requires output of type `docker`.");
        }

        return volumeMounts;
    }

    /**
     * Generates list of container environment variables for the builder pod. Currently contains only the proxy env vars
     * and any user defined vars from the templates.
     *
     * @return  List of environment variables
     */
    private List<EnvVar> getBuildContainerEnvVars() {
        List<EnvVar> varList = new ArrayList<>();

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateBuildContainerEnvVars);

        return varList;
    }

    /**
     * Generates the builder container with the Kaniko executor
     *
     * @param imagePullPolicy   Image pull policy
     *
     * @return  Builder container definition which will be used in the Pod
     */
    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
        List<Container> containers = new ArrayList<>(1);

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(getImage())
                .withArgs("--dockerfile=/dockerfile/Dockerfile",
                        "--context=dir://workspace",
                        "--image-name-with-digest-file=/dev/termination-log",
                        "--destination=" + build.getOutput().getImage())
                .withVolumeMounts(getVolumeMounts())
                .withResources(build.getResources())
                .withSecurityContext(templateBuildContainerSecurityContext)
                .withEnv(getBuildContainerEnvVars())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .build();

        containers.add(container);

        return containers;
    }

    /**
     * This methd should return the name of the logging configuration file. But the Kaniko builder is not using any
     * logging configuration, so this currently just returns an unsupported exception (but it has to exist due to the
     * inheritance).
     *
     * @return
     */
    @Override
    protected String getDefaultLogConfigFileName() {
        throw new UnsupportedOperationException("Kafka Connect Build does not have any logging properties");
    }

    /**
     * Generates a BuildConfig which will be used to build new container images with additional connector plugins on OCP.
     *
     * @param dockerfile
     *
     * @return  OpenShift BuildConfig for building new container images on OpenShift
     */
    public BuildConfig generateBuildConfig(KafkaConnectDockerfile dockerfile)    {
        BuildOutput output;

        if (build.getOutput() instanceof DockerOutput) {
            DockerOutput dockerOutput = (DockerOutput) build.getOutput();

            output = new BuildOutputBuilder()
                    .withNewTo()
                        .withKind("DockerImage")
                        .withName(dockerOutput.getImage())
                    .endTo()
                    .build();

            if (dockerOutput.getPushSecret() != null) {
                output.setPushSecret(new LocalObjectReferenceBuilder().withName(dockerOutput.getPushSecret()).build());
            }
        } else if (build.getOutput() instanceof ImageStreamOutput)  {
            ImageStreamOutput imageStreamOutput = (ImageStreamOutput) build.getOutput();

            output = new BuildOutputBuilder()
                    .withNewTo()
                        .withKind("ImageStreamTag")
                        .withName(imageStreamOutput.getImage())
                    .endTo()
                    .build();
        } else {
            throw new RuntimeException("Unknown output type " + build.getOutput().getType());
        }

        return new BuildConfigBuilder()
                .withNewMetadata()
                    .withName(KafkaConnectResources.buildConfigName(cluster))
                    .withLabels(getLabelsWithStrimziName(name, templateBuildConfigLabels).toMap())
                    .withAnnotations(templateBuildConfigAnnotations)
                    .withNamespace(namespace)
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withOutput(output)
                    .withNewSource()
                        .withDockerfile(dockerfile.getDockerfile())
                    .endSource()
                    .withTriggers(new BuildTriggerPolicyBuilder().withType("ConfigChange").build())
                    .withNewStrategy()
                        .withNewDockerStrategy()
                        .endDockerStrategy()
                    .endStrategy()
                    .withResources(build.getResources())
                    .withRunPolicy("Serial")
                    .withFailedBuildsHistoryLimit(5)
                    .withSuccessfulBuildsHistoryLimit(5)
                    .withFailedBuildsHistoryLimit(5)
                .endSpec()
                .build();
    }
}

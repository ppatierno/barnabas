/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.KeyToPath;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.SecretVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.strimzi.api.kafka.model.CertificateAuthority;
import io.strimzi.api.kafka.model.storage.EphemeralStorage;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.TlsSidecar;
import io.strimzi.api.kafka.model.TlsSidecarLogLevel;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.KafkaUpgradeException;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class ModelUtils {

    public static final io.strimzi.api.kafka.model.Probe DEFAULT_TLS_SIDECAR_PROBE = new io.strimzi.api.kafka.model.ProbeBuilder()
            .withInitialDelaySeconds(TlsSidecar.DEFAULT_HEALTHCHECK_DELAY)
            .withTimeoutSeconds(TlsSidecar.DEFAULT_HEALTHCHECK_TIMEOUT)
            .build();

    private ModelUtils() {}

    protected static final Logger log = LogManager.getLogger(ModelUtils.class.getName());

    public static final String KUBERNETES_SERVICE_DNS_DOMAIN =
            System.getenv().getOrDefault("KUBERNETES_SERVICE_DNS_DOMAIN", "cluster.local");

    /**
     * @param certificateAuthority The CA configuration.
     * @return The cert validity.
     */
    public static int getCertificateValidity(CertificateAuthority certificateAuthority) {
        int validity = CertificateAuthority.DEFAULT_CERTS_VALIDITY_DAYS;
        if (certificateAuthority != null
                && certificateAuthority.getValidityDays() > 0) {
            validity = certificateAuthority.getValidityDays();
        }
        return validity;
    }

    /**
     * @param certificateAuthority The CA configuration.
     * @return The renewal days.
     */
    public static int getRenewalDays(CertificateAuthority certificateAuthority) {
        int renewalDays = CertificateAuthority.DEFAULT_CERTS_RENEWAL_DAYS;

        if (certificateAuthority != null
                && certificateAuthority.getRenewalDays() > 0) {
            renewalDays = certificateAuthority.getRenewalDays();
        }

        return renewalDays;
    }

    /**
     * Generate labels used by entity-operators to find the resources related to given cluster
     *
     * @param cluster   Name of the cluster
     * @return  Map with label definition
     */
    public static String defaultResourceLabels(String cluster) {
        return String.format("%s=%s",
                Labels.STRIMZI_CLUSTER_LABEL, cluster);
    }

    /**
     * @param sts The StatefulSet
     * @param containerName The name of the container whoes environment variables are to be retrieved
     * @return The environment of the Kafka container in the sts.
     */
    public static Map<String, String> getContainerEnv(StatefulSet sts, String containerName) {
        for (Container container : sts.getSpec().getTemplate().getSpec().getContainers()) {
            if (containerName.equals(container.getName())) {
                LinkedHashMap<String, String> map = new LinkedHashMap<>(container.getEnv() == null ? 2 : container.getEnv().size());
                if (container.getEnv() != null) {
                    for (EnvVar envVar : container.getEnv()) {
                        map.put(envVar.getName(), envVar.getValue());
                    }
                }
                return map;
            }
        }
        throw new KafkaUpgradeException("Could not find '" + containerName + "' container in StatefulSet " + sts.getMetadata().getName());
    }

    public static List<EnvVar> envAsList(Map<String, String> env) {
        ArrayList<EnvVar> result = new ArrayList<>(env.size());
        for (Map.Entry<String, String> entry : env.entrySet()) {
            result.add(new EnvVar(entry.getKey(), entry.getValue(), null));
        }
        return result;
    }

    protected static ProbeBuilder newProbeBuilder(io.strimzi.api.kafka.model.Probe userProbe) {
        return new ProbeBuilder()
                .withInitialDelaySeconds(userProbe.getInitialDelaySeconds())
                .withTimeoutSeconds(userProbe.getTimeoutSeconds())
                .withPeriodSeconds(userProbe.getPeriodSeconds())
                .withSuccessThreshold(userProbe.getSuccessThreshold())
                .withFailureThreshold(userProbe.getFailureThreshold());
    }


    protected static Probe createTcpSocketProbe(int port, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = ModelUtils.newProbeBuilder(userProbe)
                .withNewTcpSocket()
                .withNewPort()
                .withIntVal(port)
                .endPort()
                .endTcpSocket()
                .build();
        log.trace("Created TCP socket probe {}", probe);
        return probe;
    }

    protected static Probe createHttpProbe(String path, String port, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = ModelUtils.newProbeBuilder(userProbe).withNewHttpGet()
                .withPath(path)
                .withNewPort(port)
                .endHttpGet()
                .build();
        log.trace("Created http probe {}", probe);
        return probe;
    }

    static Probe createExecProbe(List<String> command, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = newProbeBuilder(userProbe).withNewExec()
                .withCommand(command)
                .endExec()
                .build();
        log.trace("Created exec probe {}", probe);
        return probe;
    }

    static Probe tlsSidecarReadinessProbe(TlsSidecar tlsSidecar) {
        io.strimzi.api.kafka.model.Probe tlsSidecarReadinessProbe;
        if (tlsSidecar != null && tlsSidecar.getReadinessProbe() != null) {
            tlsSidecarReadinessProbe = tlsSidecar.getReadinessProbe();
        } else {
            tlsSidecarReadinessProbe = DEFAULT_TLS_SIDECAR_PROBE;
        }
        return createExecProbe(Arrays.asList("/opt/stunnel/stunnel_healthcheck.sh", "2181"), tlsSidecarReadinessProbe);
    }

    static Probe tlsSidecarLivenessProbe(TlsSidecar tlsSidecar) {
        io.strimzi.api.kafka.model.Probe tlsSidecarLivenessProbe;
        if (tlsSidecar != null && tlsSidecar.getLivenessProbe() != null) {
            tlsSidecarLivenessProbe = tlsSidecar.getLivenessProbe();
        } else {
            tlsSidecarLivenessProbe = DEFAULT_TLS_SIDECAR_PROBE;
        }
        return createExecProbe(Arrays.asList("/opt/stunnel/stunnel_healthcheck.sh", "2181"), tlsSidecarLivenessProbe);
    }

    public static final String TLS_SIDECAR_LOG_LEVEL = "TLS_SIDECAR_LOG_LEVEL";

    static EnvVar tlsSidecarLogEnvVar(TlsSidecar tlsSidecar) {
        return AbstractModel.buildEnvVar(TLS_SIDECAR_LOG_LEVEL,
                (tlsSidecar != null && tlsSidecar.getLogLevel() != null ?
                        tlsSidecar.getLogLevel() : TlsSidecarLogLevel.NOTICE).toValue());
    }

    public static Secret buildSecret(ClusterCa clusterCa, Secret secret, String namespace, String secretName,
            String commonName, String keyCertName, Labels labels, OwnerReference ownerReference, boolean isMaintenanceTimeWindowsSatisfied) {
        Map<String, String> data = new HashMap<>();
        CertAndKey certAndKey = null;
        boolean shouldBeRegenerated = false;
        List<String> reasons = new ArrayList<>(2);

        if (secret == null) {
            reasons.add("certificate doesn't exist yet");
            shouldBeRegenerated = true;
        } else {
            if (clusterCa.certRenewed() || (clusterCa.isExpiring(secret, keyCertName + ".crt") && isMaintenanceTimeWindowsSatisfied)) {
                reasons.add("certificate needs to be renewed");
                shouldBeRegenerated = true;
            }
        }

        if (shouldBeRegenerated) {
            log.debug("Certificate for pod {} need to be regenerated because:", keyCertName, String.join(", ", reasons));

            try {
                certAndKey = clusterCa.generateSignedCert(commonName, Ca.IO_STRIMZI);
            } catch (IOException e) {
                log.warn("Error while generating certificates", e);
            }

            log.debug("End generating certificates");
        } else {
            if (secret.getData().get(keyCertName + ".p12") != null &&
                    !secret.getData().get(keyCertName + ".p12").isEmpty() &&
                    secret.getData().get(keyCertName + ".password") != null &&
                    !secret.getData().get(keyCertName + ".password").isEmpty()) {
                certAndKey = new CertAndKey(
                        decodeFromSecret(secret, keyCertName + ".key"),
                        decodeFromSecret(secret, keyCertName + ".crt"),
                        null,
                        decodeFromSecret(secret, keyCertName + ".p12"),
                        new String(decodeFromSecret(secret, keyCertName + ".password"), StandardCharsets.US_ASCII)
                );
            } else {
                try {
                    // coming from an older operator version, the secret exists but without keystore and password
                    certAndKey = clusterCa.addKeyAndCertToKeyStore(commonName,
                            decodeFromSecret(secret, keyCertName + ".key"),
                            decodeFromSecret(secret, keyCertName + ".crt"));
                } catch (IOException e) {
                    log.error("Error generating the keystore for {}", keyCertName, e);
                }
            }
        }

        if (certAndKey != null) {
            data.put(keyCertName + ".key", certAndKey.keyAsBase64String());
            data.put(keyCertName + ".crt", certAndKey.certAsBase64String());
            data.put(keyCertName + ".p12", certAndKey.keyStoreAsBase64String());
            data.put(keyCertName + ".password", certAndKey.storePasswordAsBase64String());
        }

        return createSecret(secretName, namespace, labels, ownerReference, data);
    }

    public static Secret createSecret(String name, String namespace, Labels labels, OwnerReference ownerReference, Map<String, String> data) {
        if (ownerReference == null) {
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .endMetadata()
                    .withData(data).build();
        } else {
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withOwnerReferences(ownerReference)
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .endMetadata()
                    .withData(data).build();
        }
    }

    /**
     * Parses the values from the PodDisruptionBudgetTemplate in CRD model into the component model
     *
     * @param model AbstractModel class where the values from the PodDisruptionBudgetTemplate should be set
     * @param pdb PodDisruptionBudgetTemplate with the values form the CRD
     */
    public static void parsePodDisruptionBudgetTemplate(AbstractModel model, PodDisruptionBudgetTemplate pdb)   {
        if (pdb != null)  {
            if (pdb.getMetadata() != null) {
                model.templatePodDisruptionBudgetLabels = pdb.getMetadata().getLabels();
                model.templatePodDisruptionBudgetAnnotations = pdb.getMetadata().getAnnotations();
            }

            model.templatePodDisruptionBudgetMaxUnavailable = pdb.getMaxUnavailable();
        }
    }

    /**
     * Parses the values from the PodTemplate in CRD model into the component model
     *
     * @param model AbstractModel class where the values from the PodTemplate should be set
     * @param pod PodTemplate with the values form the CRD
     */

    public static void parsePodTemplate(AbstractModel model, PodTemplate pod)   {
        if (pod != null)  {
            if (pod.getMetadata() != null) {
                model.templatePodLabels = pod.getMetadata().getLabels();
                model.templatePodAnnotations = pod.getMetadata().getAnnotations();
            }

            model.templateTerminationGracePeriodSeconds = pod.getTerminationGracePeriodSeconds();
            model.templateImagePullSecrets = pod.getImagePullSecrets();
            model.templateSecurityContext = pod.getSecurityContext();
            model.templatePodPriorityClassName = pod.getPriorityClassName();
            model.templatePodSchedulerName = pod.getSchedulerName();
        }
    }

    /**
     * Returns whether the given {@code Storage} instance is a persistent claim one or
     * a JBOD containing at least one persistent volume.
     *
     * @param storage the Storage instance to check
     * @return Whether the give Storage contains any persistent storage.
     */
    public static boolean containsPersistentStorage(Storage storage) {
        boolean isPersistentClaimStorage = storage instanceof PersistentClaimStorage;

        if (!isPersistentClaimStorage && storage instanceof JbodStorage) {
            isPersistentClaimStorage |= ((JbodStorage) storage).getVolumes()
                    .stream().anyMatch(volume -> volume instanceof PersistentClaimStorage);
        }
        return isPersistentClaimStorage;
    }

    /**
     * Returns the prefix used for volumes and persistent volume claims
     *
     * @param id identification number of the persistent storage
     * @return The volume prefix.
     */
    public static String getVolumePrefix(Integer id) {
        return id == null ? AbstractModel.VOLUME_NAME : AbstractModel.VOLUME_NAME + "-" + id;
    }

    public static Storage decodeStorageFromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, Storage.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String encodeStorageToJson(Storage storage) {
        try {
            return new ObjectMapper().writeValueAsString(storage);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets a map with custom labels or annotations from an environment variable
     *
     * @param envVarName Name of the environment variable which should be used as input
     *
     * @return A map with labels or annotations
     */
    public static Map<String, String> getCustomLabelsOrAnnotations(String envVarName)   {
        return Util.parseMap(System.getenv().get(envVarName));
    }

    private static byte[] decodeFromSecret(Secret secret, String key) {
        return Base64.getDecoder().decode(secret.getData().get(key));
    }

    /**
     * Compares two Secrets with certificates and checks whether any value for a key which exists in both Secrets
     * changed. This method is used to evaluate whether rolling update of existing brokers is needed when secrets with
     * certificates change. It separates changes for existing certificates with other changes to the secret such as
     * added or removed certificates (scale-up or scale-down).
     *
     * @param current   Existing secret
     * @param desired   Desired secret
     *
     * @return  True if there is a key which exists in the data sections of both secrets and which changed.
     */
    public static boolean doExistingCertificatesDiffer(Secret current, Secret desired) {
        Map<String, String> currentData = current.getData();
        Map<String, String> desiredData = desired.getData();

        for (Map.Entry<String, String> entry : currentData.entrySet()) {
            String desiredValue = desiredData.get(entry.getKey());
            if (entry.getValue() != null
                    && desiredValue != null
                    && !entry.getValue().equals(desiredValue)) {
                return true;
            }
        }

        return false;
    }

    public static List<VolumeMount> getDataVolumeMountPaths(Storage storage, String mountPath)   {
        List<VolumeMount> volumeMounts = new ArrayList<>();

        if (storage != null) {
            if (storage instanceof JbodStorage) {
                for (SingleVolumeStorage volume : ((JbodStorage) storage).getVolumes()) {
                    if (volume.getId() == null)
                        throw new InvalidResourceException("Volumes under JBOD storage type have to have 'id' property");
                    // it's called recursively for setting the information from the current volume
                    volumeMounts.addAll(getDataVolumeMountPaths(volume, mountPath));
                }
            } else {
                Integer id;

                if (storage instanceof EphemeralStorage) {
                    id = ((EphemeralStorage) storage).getId();
                } else if (storage instanceof PersistentClaimStorage) {
                    id = ((PersistentClaimStorage) storage).getId();
                } else {
                    throw new IllegalStateException("The declared storage '" + storage.getType() + "' is not supported");
                }

                String name = getVolumePrefix(id);
                String namedMountPath = mountPath + "/" + name;
                volumeMounts.add(createVolumeMount(name, namedMountPath));
            }
        }

        return volumeMounts;
    }

    public static List<PersistentVolumeClaim> getDataPersistentVolumeClaims(Storage storage)   {
        List<PersistentVolumeClaim> pvcs = new ArrayList<>();

        if (storage != null) {
            if (storage instanceof JbodStorage) {
                for (SingleVolumeStorage volume : ((JbodStorage) storage).getVolumes()) {
                    if (volume.getId() == null)
                        throw new InvalidResourceException("Volumes under JBOD storage type have to have 'id' property");
                    // it's called recursively for setting the information from the current volume
                    pvcs.addAll(getDataPersistentVolumeClaims(volume));
                }
            } else if (storage instanceof PersistentClaimStorage) {
                Integer id = ((PersistentClaimStorage) storage).getId();
                String name = getVolumePrefix(id);
                pvcs.add(createPersistentVolumeClaimTemplate(name, (PersistentClaimStorage) storage));
            }
        }

        return pvcs;
    }

    public static List<Volume> getDataVolumes(Storage storage)   {
        List<Volume> volumes = new ArrayList<>();

        if (storage != null) {
            if (storage instanceof JbodStorage) {
                for (SingleVolumeStorage volume : ((JbodStorage) storage).getVolumes()) {
                    if (volume.getId() == null)
                        throw new InvalidResourceException("Volumes under JBOD storage type have to have 'id' property");
                    // it's called recursively for setting the information from the current volume
                    volumes.addAll(getDataVolumes(volume));
                }
            } else if (storage instanceof EphemeralStorage) {
                Integer id = ((EphemeralStorage) storage).getId();
                String name = getVolumePrefix(id);
                String sizeLimit = ((EphemeralStorage) storage).getSizeLimit();
                volumes.add(createEmptyDirVolume(name, sizeLimit));
            }
        }

        return volumes;
    }

    public static VolumeMount createVolumeMount(String name, String path) {
        VolumeMount volumeMount = new VolumeMountBuilder()
                .withName(name)
                .withMountPath(path)
                .build();
        log.trace("Created volume mount {}", volumeMount);
        return volumeMount;
    }

    public static PersistentVolumeClaim createPersistentVolumeClaimTemplate(String name, PersistentClaimStorage storage) {
        Map<String, Quantity> requests = new HashMap<>();
        requests.put("storage", new Quantity(storage.getSize(), null));

        LabelSelector selector = null;
        if (storage.getSelector() != null && !storage.getSelector().isEmpty()) {
            selector = new LabelSelector(null, storage.getSelector());
        }

        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .withRequests(requests)
                    .endResources()
                    .withStorageClassName(storage.getStorageClass())
                    .withSelector(selector)
                .endSpec()
                .build();

        return pvc;
    }

    public static Volume createEmptyDirVolume(String name, String sizeLimit) {
        EmptyDirVolumeSource emptyDirVolumeSource = new EmptyDirVolumeSourceBuilder().build();
        if (sizeLimit != null && !sizeLimit.isEmpty()) {
            emptyDirVolumeSource.setSizeLimit(new Quantity(sizeLimit));
        }

        Volume volume = new VolumeBuilder()
            .withName(name)
                .withEmptyDir(emptyDirVolumeSource)
            .build();
        log.trace("Created emptyDir Volume named '{}' with sizeLimit '{}'", name, sizeLimit);
        return volume;
    }

    public static Volume createSecretVolume(String name, String secretName, boolean isOpenshift) {
        int mode = 0444;
        if (isOpenshift) {
            mode = 0440;
        }

        SecretVolumeSource secretVolumeSource = new SecretVolumeSourceBuilder()
                .withDefaultMode(mode)
                .withSecretName(secretName)
                .build();

        Volume volume = new VolumeBuilder()
                .withName(name)
                .withSecret(secretVolumeSource)
                .build();
        log.trace("Created secret Volume named '{}' with source secret '{}'", name, secretName);
        return volume;
    }

    public static Volume createSecretVolume(String name, String secretName, Map<String, String> items, boolean isOpenshift) {
        int mode = 0444;
        if (isOpenshift) {
            mode = 0440;
        }

        List<KeyToPath> keysPaths = new ArrayList<>();

        for (Map.Entry<String, String> item : items.entrySet()) {
            KeyToPath keyPath = new KeyToPathBuilder()
                    .withNewKey(item.getKey())
                    .withNewPath(item.getValue())
                    .build();

            keysPaths.add(keyPath);
        }

        SecretVolumeSource secretVolumeSource = new SecretVolumeSourceBuilder()
                .withDefaultMode(mode)
                .withSecretName(secretName)
                .withItems(keysPaths)
                .build();

        Volume volume = new VolumeBuilder()
                .withName(name)
                .withSecret(secretVolumeSource)
                .build();
        log.trace("Created secret Volume named '{}' with source secret '{}'", name, secretName);
        return volume;
    }
}

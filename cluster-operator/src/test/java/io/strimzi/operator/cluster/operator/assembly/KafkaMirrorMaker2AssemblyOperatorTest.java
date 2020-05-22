/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicy;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2Resources;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.KafkaMirrorMaker2Cluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.NetworkPolicyOperator;
import io.strimzi.operator.common.operator.resource.PodDisruptionBudgetOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.strimzi.test.TestUtils;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaMirrorMaker2AssemblyOperatorTest {

    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    protected static Vertx vertx;
    private static final String METRICS_CONFIG = "{\"foo\":\"bar\"}";
    private static final String LOGGING_CONFIG = AbstractModel.getOrderedProperties("kafkaMirrorMaker2DefaultLoggingProperties")
            .asPairsWithComment("Do not change this generated file. Logging can be configured in the corresponding kubernetes/openshift resource.");

    private final KubernetesVersion kubernetesVersion = KubernetesVersion.V1_9;

    @BeforeAll
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void after() {
        vertx.close();
    }

    @Test
    public void testCreateCluster(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";
        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);

        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(anyString(), anyString(), dcCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), x -> mockConnectClient);

        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                // No metrics config  => no CMs created
                Set<String> metricsNames = new HashSet<>();
                if (mirrorMaker2.isMetricsEnabled()) {
                    metricsNames.add(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(clusterCmName));
                }

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(1));
                Service service = capturedServices.get(0);
                assertThat(service.getMetadata().getName(), is(mirrorMaker2.getServiceName()));
                assertThat(service, is(mirrorMaker2.generateService()));

                // Verify Deployment
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));
                Deployment dc = capturedDc.get(0);
                assertThat(dc.getMetadata().getName(), is(mirrorMaker2.getName()));
                Map annotations = new HashMap();
                annotations.put(Annotations.STRIMZI_LOGGING_ANNOTATION, LOGGING_CONFIG);
                assertThat(dc, is(mirrorMaker2.generateDeployment(annotations, true, null, null)));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getName()));
                assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget()));

                // Verify status
                List<KafkaMirrorMaker2> capturedMirrorMaker2s = mirrorMaker2Captor.getAllValues();
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getUrl(), is("http://foo-mirrormaker2-api.test.svc:8083"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getType(), is("Ready"));
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterNoDiff(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);
        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateDeployment(new HashMap<String, String>(), true, null, null));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(eq(clusterCmNamespace), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(eq(clusterCmNamespace), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(eq(clusterCmNamespace), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(eq(clusterCmNamespace), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(1));

                // Verify Deployment Config
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));

                // Verify scaleDown / scaleUp were not called
                assertThat(dcScaleDownNameCaptor.getAllValues(), hasSize(1));
                assertThat(dcScaleUpNameCaptor.getAllValues(), hasSize(1));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getName()));
                assertThat("PodDisruptionBudgets are not equal", pdb, is(mirrorMaker2.generatePodDisruptionBudget()));

                async.flag();
            })));
    }

    @Test
    public void testUpdateCluster(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);
        clusterCm.getSpec().setImage("some/different:image"); // Change the image to generate some diff
        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.get(clusterCmNamespace, mirrorMaker2.getName()))
                .thenReturn(mirrorMaker2.generateDeployment(new HashMap<String, String>(), true, null, null));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(eq(clusterCmNamespace), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(eq(clusterCmNamespace), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(eq(clusterCmNamespace), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(eq(clusterCmNamespace), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any()))
                .thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        // Mock CM get
        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        ConfigMap metricsCm = new ConfigMapBuilder().withNewMetadata()
                    .withName(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(clusterCmName))
                    .withNamespace(clusterCmNamespace)
                .endMetadata()
                .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_METRICS, METRICS_CONFIG))
                .build();
        when(mockCmOps.get(clusterCmNamespace, KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(clusterCmName))).thenReturn(metricsCm);

        ConfigMap loggingCm = new ConfigMapBuilder().withNewMetadata()
                    .withName(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(clusterCmName))
                    .withNamespace(clusterCmNamespace)
                    .endMetadata()
                    .withData(Collections.singletonMap(AbstractModel.ANCILLARY_CM_KEY_LOG_CONFIG, LOGGING_CONFIG))
                    .build();

        when(mockCmOps.get(clusterCmNamespace, KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(clusterCmName))).thenReturn(metricsCm);

        // Mock CM patch
        Set<String> metricsCms = TestUtils.set();
        doAnswer(invocation -> {
            metricsCms.add(invocation.getArgument(1));
            return Future.succeededFuture();
        }).when(mockCmOps).reconcile(eq(clusterCmNamespace), anyString(), any());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                KafkaMirrorMaker2Cluster compareTo = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(1));
                Service service = capturedServices.get(0);
                assertThat(service.getMetadata().getName(), is(compareTo.getServiceName()));
                assertThat(service, is(compareTo.generateService()));

                // Verify Deployment
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));
                Deployment dc = capturedDc.get(0);
                assertThat(dc.getMetadata().getName(), is(compareTo.getName()));
                Map<String, String> annotations = new HashMap();
                annotations.put(Annotations.STRIMZI_LOGGING_ANNOTATION, loggingCm.getData().get(compareTo.ANCILLARY_CM_KEY_LOG_CONFIG));
                assertThat(dc, is(compareTo.generateDeployment(annotations, true, null, null)));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getName()));
                assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget()));

                // Verify scaleDown / scaleUp were not called
                assertThat(dcScaleDownNameCaptor.getAllValues(), hasSize(1));
                assertThat(dcScaleUpNameCaptor.getAllValues(), hasSize(1));

                // No metrics config  => no CMs created
                verify(mockCmOps, never()).createOrUpdate(any());
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterWithFailingDeploymentFailure(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);
        clusterCm.getSpec().setImage("some/different:image"); // Change the image to generate some diff

        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateDeployment(new HashMap<String, String>(), true, null, null));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(dcNamespaceCaptor.capture(), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.failedFuture("Failed"));

        ArgumentCaptor<String> dcScaleUpNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(dcScaleUpNamespaceCaptor.capture(), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(dcScaleDownNamespaceCaptor.capture(), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockMirrorMaker2Ops.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS));

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.failing(v -> async.flag()));
    }

    @Test
    public void testUpdateClusterScaleUp(VertxTestContext context) {
        final int scaleTo = 4;

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);
        clusterCm.getSpec().setReplicas(scaleTo); // Change replicas to create ScaleUp

        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateDeployment(new HashMap<String, String>(), true, null, null));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockServiceOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());

        when(mockDcOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);

        when(mockMirrorMaker2Ops.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                verify(mockDcOps).scaleUp(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterScaleDown(VertxTestContext context) {
        int scaleTo = 2;

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(clusterCm, VERSIONS);
        clusterCm.getSpec().setReplicas(scaleTo); // Change replicas to create ScaleDown

        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.get(clusterCmNamespace, mirrorMaker2.getName())).thenReturn(mirrorMaker2.generateDeployment(new HashMap<String, String>(), true, null, null));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockServiceOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());

        when(mockDcOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);

        when(mockMirrorMaker2Ops.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                verify(mockDcOps).scaleUp(clusterCmNamespace, mirrorMaker2.getName(), scaleTo);
                async.flag();
            })));
    }

    @Test
    public void testReconcile(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        SecretOperator mockSecretOps = supplier.secretOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;

        String clusterCmNamespace = "test";

        KafkaMirrorMaker2 foo = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, "foo");
        KafkaMirrorMaker2 bar = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, "bar");
        when(mockMirrorMaker2Ops.listAsync(eq(clusterCmNamespace), any(Optional.class))).thenReturn(Future.succeededFuture(asList(foo, bar)));
        // when requested ConfigMap for a specific Kafka MirrorMaker 2.0 cluster
        when(mockMirrorMaker2Ops.get(eq(clusterCmNamespace), eq("foo"))).thenReturn(foo);
        when(mockMirrorMaker2Ops.get(eq(clusterCmNamespace), eq("bar"))).thenReturn(bar);

        // providing the list of ALL Deployments for all the Kafka MirrorMaker 2.0 clusters
        Labels newLabels = Labels.forStrimziKind(KafkaMirrorMaker2.RESOURCE_KIND);
        when(mockDcOps.list(eq(clusterCmNamespace), eq(newLabels))).thenReturn(
                asList(KafkaMirrorMaker2Cluster.fromCrd(bar, VERSIONS).generateDeployment(new HashMap<String, String>(), true, null, null)));

        // providing the list Deployments for already "existing" Kafka MirrorMaker 2.0 clusters
        Labels barLabels = Labels.forStrimziCluster("bar");
        when(mockDcOps.list(eq(clusterCmNamespace), eq(barLabels))).thenReturn(
                asList(KafkaMirrorMaker2Cluster.fromCrd(bar, VERSIONS).generateDeployment(new HashMap<String, String>(), true, null, null))
        );
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());
        when(mockPdbOps.reconcile(eq(clusterCmNamespace), any(), any())).thenReturn(Future.succeededFuture());

        Set<String> createdOrUpdated = new CopyOnWriteArraySet<>();

        Checkpoint createOrUpdateAsync = context.checkpoint(2);
        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS)) {

            @Override
            public Future<Void> createOrUpdate(Reconciliation reconciliation, KafkaMirrorMaker2 kafkaMirrorMaker2Assembly) {
                createdOrUpdated.add(kafkaMirrorMaker2Assembly.getMetadata().getName());
                createOrUpdateAsync.flag();
                return Future.succeededFuture();
            }
        };

        Checkpoint async = context.checkpoint();
        // Now try to reconcile all the Kafka MirrorMaker 2.0 clusters
        ops.reconcileAll("test", clusterCmNamespace,
            context.succeeding(v -> context.verify(() -> {
                assertThat(createdOrUpdated, is(new HashSet(asList("foo", "bar"))));
                async.flag();
            })));

    }

    @Test
    public void testCreateClusterStatusNotReady(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";
        String failureMsg = "failure";
        KafkaMirrorMaker2 clusterCm = ResourceUtils.createEmptyKafkaMirrorMaker2Cluster(clusterCmNamespace, clusterCmName);

        when(mockMirrorMaker2Ops.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(clusterCm));
        when(mockServiceOps.reconcile(anyString(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(anyString(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(anyString(), anyString(), anyInt())).thenReturn(Future.failedFuture(failureMsg));
        when(mockDcOps.readiness(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(eq(clusterCm.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(clusterCm.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));
        when(mockPdbOps.reconcile(anyString(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig(VERSIONS));

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, clusterCmNamespace, clusterCmName), clusterCm)
            .onComplete(context.failing(v -> context.verify(() -> {
                // Verify status
                List<KafkaMirrorMaker2> capturedMirrorMaker2s = mirrorMaker2Captor.getAllValues();
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getUrl(), is("http://foo-mirrormaker2-api.test.svc:8083"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getType(), is("NotReady"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getMessage(), is(failureMsg));
                async.flag();
            })));
    }

}

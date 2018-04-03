/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operations.resource;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.strimzi.controller.cluster.resources.Labels;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Abstract resource creation, for a generic resource type {@code R}.
 * This class applies the template method pattern, first checking whether the resource exists,
 * and creating it if it does not. It is not an error if the resource did already exist.
 * @param <C> The type of client used to interact with kubernetes.
 * @param <T> The Kubernetes resource type.
 * @param <L> The list variant of the Kubernetes resource type.
 * @param <D> The doneable variant of the Kubernetes resource type.
 * @param <R> The resource operations.
 */
public abstract class AbstractOperations<C, T extends HasMetadata, L extends KubernetesResourceList/*<T>*/, D, R extends Resource<T, D>> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOperations.class);
    protected final Vertx vertx;
    protected final C client;
    protected final String resourceKind;

    /**
     * Constructor.
     * @param vertx The vertx instance.
     * @param client The kubernetes client.
     * @param resourceKind The mind of Kubernetes resource (used for logging).
     */
    public AbstractOperations(Vertx vertx, C client, String resourceKind) {
        this.vertx = vertx;
        this.client = client;
        this.resourceKind = resourceKind;
    }

    protected abstract MixedOperation<T, L, D, R> operation();

    /**
     * Asynchronously create or update the given {@code resource} depending on whether it already exists,
     * returning a future for the outcome.
     * If the resource with that name already exists the future completes successfully.
     * @param resource The resource to create.
     */
    public Future<Void> createOrUpdate(T resource) {
        if (resource == null) {
            throw new NullPointerException();
        }
        return reconcile(resource.getMetadata().getNamespace(), resource.getMetadata().getName(), resource);
    }

    /**
     * Asynchronously reconciles the resource with the given namespace and name to match the given
     * desired resource, returning a future for the result.
     */
    public Future<Void> reconcile(String namespace, String name, T desired) {
        Future<Void> fut = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    if (desired != null) {
                        if (!namespace.equals(desired.getMetadata().getNamespace())) {
                            future.fail("Given namespace " + namespace + " incompatible with desired namespace " + desired.getMetadata().getNamespace());
                        } else if (!name.equals(desired.getMetadata().getName())) {
                            future.fail("Given name "+ name +" incompatible with desired name " + desired.getMetadata().getName());
                        } else {
                            T current = operation().inNamespace(namespace).withName(name).get();
                            if (current == null) {
                                internalCreate(namespace, name, desired, future);
                            } else {
                                internalPatch(namespace, name, current, desired, future);
                            }
                        }
                    } else {
                        // Deletion is desired
                        internalDelete(namespace, name, future);
                    }
                },
                false,
                fut.completer()
        );
        return fut;
    }

    /**
     * Deletes the resource with the given namespace and name
     * and completes the given future accordingly
     */
    protected void internalDelete(String namespace, String name, Future<Void> future) {
        try {
            log.info("Deleting {} {} in namespace {}", resourceKind, name, namespace);
            operation().inNamespace(namespace).withName(name).delete();
            log.info("{} {} in namespace {} has been deleted", resourceKind, name, namespace);
            future.complete();
        } catch (Exception e) {
            log.error("Caught exception while deleting {} {} in namespace {}", resourceKind, name, namespace, e);
            future.fail(e);
        }
    }

    /**
     * Patches the resource with the given namespace and name to match the given desired resource
     * and completes the given future accordingly.
     */
    protected void internalPatch(String namespace, String name, T current, T desired, Future<Void> future) {
        try {
            log.info("Patching {} resource {} in namespace {} with {}", resourceKind, name, namespace, desired);
            operation().inNamespace(namespace).withName(name).cascading(true).patch(desired);
            log.info("{} {} in namespace {} has been patched", resourceKind, name, namespace);
            future.complete();
        } catch (Exception e) {
            log.error("Caught exception while patching {} {} in namespace {}", resourceKind, name, namespace, e);
            future.fail(e);
        }
    }

    /**
     * Creates a resource with the given namespace and name with the given desired state
     * and completes the given future accordingly.
     */
    protected void internalCreate(String namespace, String name, T desired, Future<Void> future) {
        try {
            log.info("Creating {} {} in namespace {}", resourceKind, name, namespace);
            operation().inNamespace(namespace).withName(name).create(desired);
            log.info("{} {} in namespace {} has been created", resourceKind, name, namespace);
            future.complete();
        } catch (Exception e) {
            log.error("Caught exception while creating {} {} in namespace {}", resourceKind, name, namespace, e);
            future.fail(e);
        }
    }

    /**
     * Synchronously gets the resource with the given {@code name} in the given {@code namespace}.
     * @param namespace The namespace.
     * @param name The name.
     * @return The resource, or null if it doesn't exist.
     */
    public T get(String namespace, String name) {
        return operation().inNamespace(namespace).withName(name).get();
    }

    /**
     * Synchronously list the resources in the given {@code namespace} with the given {@code selector}.
     * @param namespace The namespace.
     * @param selector The selector.
     * @return A list of matching resources.
     */
    @SuppressWarnings("unchecked")
    public List<T> list(String namespace, Labels selector) {
        return operation().inNamespace(namespace).withLabels(selector.toMap()).list().getItems();
    }
}

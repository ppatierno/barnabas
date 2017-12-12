package io.enmasse.barnabas.controller.cluster.operations.cluster;

import io.enmasse.barnabas.controller.cluster.K8SUtils;
import io.enmasse.barnabas.controller.cluster.operations.OperationExecutor;
import io.enmasse.barnabas.controller.cluster.operations.kubernetes.CreateServiceOperation;
import io.enmasse.barnabas.controller.cluster.operations.kubernetes.CreateStatefulSetOperation;
import io.enmasse.barnabas.controller.cluster.resources.KafkaResource;
import io.vertx.core.*;
import io.vertx.core.shareddata.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateKafkaClusterOperation extends KafkaClusterOperation {
    private static final Logger log = LoggerFactory.getLogger(CreateKafkaClusterOperation.class.getName());

    public CreateKafkaClusterOperation(String namespace, String name) {
        super(namespace, name);
    }

    @Override
    public void execute(Vertx vertx, K8SUtils k8s, Handler<AsyncResult<Void>> handler) {
        vertx.sharedData().getLockWithTimeout(getLockName(), LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                Lock lock = res.result();

                log.info("Creating Kafka cluster {} in namespace {}", name, namespace);

                KafkaResource kafka = KafkaResource.fromConfigMap(k8s.getConfigmap(namespace, name));

                Future<Void> futureService = Future.future();
                OperationExecutor.getInstance().execute(new CreateServiceOperation(kafka.generateService()), futureService.completer());

                Future<Void> futureHeadlessService = Future.future();
                OperationExecutor.getInstance().execute(new CreateServiceOperation(kafka.generateHeadlessService()), futureHeadlessService.completer());

                Future<Void> futureStatefulSet = Future.future();
                OperationExecutor.getInstance().execute(new CreateStatefulSetOperation(kafka.generateStatefulSet()), futureStatefulSet.completer());

                CompositeFuture.join(futureService, futureHeadlessService, futureStatefulSet).setHandler(ar -> {
                    if (ar.succeeded()) {
                        log.info("Kafka cluster {} successfully created in namespace {}", name, namespace);
                        handler.handle(Future.succeededFuture());
                        lock.release();
                    } else {
                        log.error("Kafka cluster {} failed to create in namespace {}", name, namespace);
                        handler.handle(Future.failedFuture("Failed to create Kafka cluster"));
                        lock.release();
                    }
                });
            } else {
                log.error("Failed to acquire lock to create Kafka cluster {}", getLockName());
                handler.handle(Future.failedFuture("Failed to acquire lock to create Kafka cluster"));
            }
        });
    }
}

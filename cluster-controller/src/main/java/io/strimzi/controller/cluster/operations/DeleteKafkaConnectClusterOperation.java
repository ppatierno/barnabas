package io.strimzi.controller.cluster.operations;

import io.strimzi.controller.cluster.K8SUtils;
import io.strimzi.controller.cluster.operations.openshift.DeleteS2IOperation;
import io.strimzi.controller.cluster.resources.KafkaConnectCluster;
import io.vertx.core.*;
import io.vertx.core.shareddata.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteKafkaConnectClusterOperation extends KafkaConnectClusterOperation {
    private static final Logger log = LoggerFactory.getLogger(DeleteKafkaConnectClusterOperation.class.getName());

    public DeleteKafkaConnectClusterOperation(String namespace, String name) {
        super(namespace, name);
    }

    @Override
    public void execute(Vertx vertx, K8SUtils k8s, Handler<AsyncResult<Void>> handler) {
        vertx.sharedData().getLockWithTimeout(getLockName(), LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                Lock lock = res.result();

                KafkaConnectCluster connect = KafkaConnectCluster.fromDeployment(k8s, namespace, name);

                log.info("Deleting Kafka Connect cluster {} from namespace {}", connect.getName(), namespace);

                Future<Void> futureService = Future.future();
                OperationExecutor.getInstance().executeFabric8(DeleteOperation.deleteService(namespace, connect.getName()), futureService.completer());

                Future<Void> futureDeployment = Future.future();
                OperationExecutor.getInstance().executeK8s(DeleteOperation.deleteDeployment(namespace, connect.getName()), futureDeployment.completer());

                Future<Void> futureS2I;
                if (connect.getS2I() != null) {
                    futureS2I = Future.future();
                    OperationExecutor.getInstance().executeOpenShift(new DeleteS2IOperation(connect.getS2I()), futureS2I.completer());
                } else {
                    futureS2I = Future.succeededFuture();
                }

                CompositeFuture.join(futureService, futureDeployment, futureS2I).setHandler(ar -> {
                    if (ar.succeeded()) {
                        log.info("Kafka Connect cluster {} successfully deleted from namespace {}", connect.getName(), namespace);
                        handler.handle(Future.succeededFuture());
                        lock.release();
                    } else {
                        log.error("Kafka Connect cluster {} failed to delete from namespace {}", connect.getName(), namespace);
                        handler.handle(Future.failedFuture("Failed to delete Kafka Connect cluster"));
                        lock.release();
                    }
                });
            } else {
                log.error("Failed to acquire lock to delete Kafka Connect cluster {}", getLockName());
                handler.handle(Future.failedFuture("Failed to acquire lock to delete Kafka Connect cluster"));
            }
        });
    }
}

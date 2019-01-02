/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyPeer;
import io.strimzi.crdgenerator.annotations.Description;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.strimzi.crdgenerator.annotations.KubeLink;
import io.sundr.builder.annotations.Buildable;

import java.util.List;

/**
 * Configures the external listener which exposes Kafka outside of Kubernetes using LoadBalancers
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "authentication"})
public class KafkaListenerExternalLoadBalancer extends KafkaListenerExternal {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_LOADBALANCER = "loadbalancer";

    private KafkaListenerAuthentication auth;
    private boolean tls = true;
    private List<NetworkPolicyPeer> networkPolicyPeers;

    @Description("Must be `" + TYPE_LOADBALANCER + "`")
    @Override
    public String getType() {
        return TYPE_LOADBALANCER;
    }

    @Description("Authentication configuration for Kafka brokers")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("authentication")
    public KafkaListenerAuthentication getAuth() {
        return auth;
    }

    public void setAuth(KafkaListenerAuthentication auth) {
        this.auth = auth;
    }

    @Description("Enables TLS encryption on the listener. " +
            "By default set to `true` for enabled TLS encryption.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    @Description("List of peers which should be able to connect to this listener. " +
            "Peers in this list are combined using a logical OR operation. " +
            "If this field is empty or missing, all connections will be allowed for this listener. " +
            "If this field is present and contains at least one item, the listener only allows the traffic which matches at least one item in this list.")
    @KubeLink(group = "networking", version = "v1", kind = "networkpolicypeer")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<NetworkPolicyPeer> getNetworkPolicyPeers() {
        return networkPolicyPeers;
    }

    public void setNetworkPolicyPeers(List<NetworkPolicyPeer> networkPolicyPeers) {
        this.networkPolicyPeers = networkPolicyPeers;
    }
}

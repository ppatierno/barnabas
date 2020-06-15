/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.DescriptionFile;
import io.strimzi.crdgenerator.annotations.Example;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Configures the broker authorization to use Open Policy Agent as an authorization and policy server.
 */
@DescriptionFile
@Buildable(
        editableEnabled = false,
        builderPackage = Constants.FABRIC8_KUBERNETES_API
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "url", "allowOnError", "initialCacheCapacity", "maximumCacheSize", "expireAfterMs", "superUsers"})
@EqualsAndHashCode
public class KafkaAuthorizationOpa extends KafkaAuthorization {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_OPA = "opa";

    public static final String AUTHORIZER_CLASS_NAME = "com.bisnode.kafka.authorization.OpaAuthorizer";

    private List<String> superUsers;
    private String url;
    private boolean allowOnError = false;
    private int initialCacheCapacity = 5000;
    private int maximumCacheSize = 50000;
    private long expireAfterMs = 3600000;

    @Description("Must be `" + TYPE_OPA + "`")
    @Override
    public String getType() {
        return TYPE_OPA;
    }

    @Description("List of super users, which is specifically a list of user principals that have unlimited access rights.")
    @Example("- CN=my-user\n" +
             "- CN=my-other-user")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getSuperUsers() {
        return superUsers;
    }

    public void setSuperUsers(List<String> superUsers) {
        this.superUsers = superUsers;
    }

    @Description("The URL used to connect to the Open Policy Agent server. " +
            "The URL has to include the policy which will be queried by the authorizer. " +
            "This option is required.")
    @Example("http://opa:8181/v1/data/kafka/authz/allow")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Description("Defines whether a Kafka client should be allowed or denied by default when the authorizer fails to query the Open Policy Agent, for example, when it is temporarily unavailable). " +
            "Defaults to `false` - all actions will be denied.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public boolean isAllowOnError() {
        return allowOnError;
    }

    public void setAllowOnError(boolean allowOnError) {
        this.allowOnError = allowOnError;
    }

    @Description("Initial capacity of the local cache used by the authorizer to avoid querying the Open Policy Agent for every request " +
            "Defaults to `5000`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public int getInitialCacheCapacity() {
        return initialCacheCapacity;
    }

    public void setInitialCacheCapacity(int initialCacheCapacity) {
        this.initialCacheCapacity = initialCacheCapacity;
    }

    @Description("Maximum capacity of the local cache used by the authorizer to avoid querying the Open Policy Agent for every request. " +
            "Defaults to `50000`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public int getMaximumCacheSize() {
        return maximumCacheSize;
    }

    public void setMaximumCacheSize(int maximumCacheSize) {
        this.maximumCacheSize = maximumCacheSize;
    }

    @Description("The expiration of the records kept in the local cache to avoid querying the Open Policy Agent for every request. " +
            "Defines how often the cached authorization decisions are reloaded from the Open Policy Agent server. " +
            "In milliseconds. " +
            "Defaults to `3600000`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public long getExpireAfterMs() {
        return expireAfterMs;
    }

    public void setExpireAfterMs(long expireAfterMs) {
        this.expireAfterMs = expireAfterMs;
    }
}

/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfiguration;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBroker;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.unmodifiableList;

/**
 * Util methods for validating Kafka listeners
 */
public class ListenersValidator {
    protected static final Logger LOG = LogManager.getLogger(ListenersValidator.class.getName());
    private final static Pattern LISTENER_NAME_PATTERN = Pattern.compile(GenericKafkaListener.LISTENER_NAME_REGEX);
    public final static List<Integer> FORBIDDEN_PORTS = unmodifiableList(Arrays.asList(9404, 9999));
    public final static int LOWEST_ALLOWED_PORT_NUMBER = 9092;

    /**
     * Validated the listener configuration. If the configuration is not valid, InvalidResourceException will be thrown.
     *
     * @param replicas   Number of replicas (required for Ingress validation)
     * @param listeners  Listeners which should be validated
     */
    public static void validate(int replicas, List<GenericKafkaListener> listeners) throws InvalidResourceException {
        Set<String> errors = validateAndGetErrorMessages(replicas, listeners);

        if (!errors.isEmpty())  {
            LOG.error("Listener configuration is not valid: {}", errors);
            throw new InvalidResourceException("Listener configuration is not valid: " + errors);
        }
    }

    /*test*/ static Set<String> validateAndGetErrorMessages(int replicas, List<GenericKafkaListener> listeners)    {
        Set<String> errors = new HashSet<>(0);
        List<Integer> ports = getPorts(listeners);
        List<String> names = getNames(listeners);

        if (names.size() != listeners.size())   {
            errors.add("every listener needs to have a unique name");
        }

        List<String> invalidNames = names.stream().filter(name -> !LISTENER_NAME_PATTERN.matcher(name).matches()).collect(Collectors.toList());
        if (!invalidNames.isEmpty())    {
            errors.add("listener names " + invalidNames + " are invalid and do not match the pattern " + GenericKafkaListener.LISTENER_NAME_REGEX);
        }

        if (ports.size() != listeners.size())   {
            errors.add("every listener needs to have a unique port number");
        }

        for (GenericKafkaListener listener : listeners) {
            validatePortNumbers(errors, listener);
            validateRouteAndIngressTlsOnly(errors, listener);
            validateTlsFeaturesOnNonTlsListener(errors, listener);
            validateOauth(errors, listener);

            if (listener.getConfiguration() != null)    {
                validateServiceDnsDomain(errors, listener);
                validateIngressClass(errors, listener);
                validateExternalTrafficPolicy(errors, listener);
                validateLoadBalancerSourceRanges(errors, listener);
                validatePreferredAddressType(errors, listener);

                if (listener.getConfiguration().getBootstrap() != null) {
                    validateBootstrapHost(errors, listener);
                    validateBootstrapLoadBalancerIp(errors, listener);
                    validateBootstrapNodePort(errors, listener);
                    validateBootstrapDnsAnnotations(errors, listener);
                }

                if (listener.getConfiguration().getBrokers() != null) {
                    for (GenericKafkaListenerConfigurationBroker broker : listener.getConfiguration().getBrokers()) {
                        validateBrokerHost(errors, listener, broker);
                        validateBrokerLoadBalancerIp(errors, listener, broker);
                        validateBrokerNodePort(errors, listener, broker);
                        validateBrokerDnsAnnotations(errors, listener, broker);
                    }
                }
            }

            if (KafkaListenerType.INGRESS.equals(listener.getType()))    {
                validateIngress(errors, replicas, listener);
            }
        }

        return errors;
    }

    /**
     * Validates that the listener has an allowed port number
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validatePortNumbers(Set<String> errors, GenericKafkaListener listener) {
        int port = listener.getPort();

        if (FORBIDDEN_PORTS.contains(port)
                || port < LOWEST_ALLOWED_PORT_NUMBER)    {
            errors.add("port " + port + " is forbidden and cannot be used");
        }
    }

    /**
     * Validates that Ingress type listener has the right host configurations
     *
     * @param errors    List where any found errors will be added
     * @param replicas  Number of Kafka replicas
     * @param listener  Listener which needs to be validated
     */
    private static void validateIngress(Set<String> errors, int replicas, GenericKafkaListener listener) {
        if (listener.getConfiguration() != null)    {
            GenericKafkaListenerConfiguration conf = listener.getConfiguration();

            if (conf.getBootstrap() == null
                    || conf.getBootstrap().getHost() == null)   {
                errors.add("listener " + listener.getName() + " is missing a bootstrap host name which is required for Ingress based listeners");
            }

            if (conf.getBrokers() != null) {
                for (int i = 0; i < replicas; i++)  {
                    final int id = i;
                    GenericKafkaListenerConfigurationBroker broker = conf.getBrokers().stream().filter(b -> b.getBroker() == id).findFirst().orElse(null);

                    if (broker == null || broker.getHost() == null) {
                        errors.add("listener " + listener.getName() + " is missing a broker host name for broker with ID " + i + " which is required for Ingress based listeners");
                    }
                }
            } else {
                errors.add("listener " + listener.getName() + " is missing a broker configuration with host names which is required for Ingress based listeners");
            }


        } else {
            errors.add("listener " + listener.getName() + " is missing a configuration with host names which is required for Ingress based listeners");
        }
    }

    /**
     * Validates that useServiceDnsDomain is used only with internal type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateServiceDnsDomain(Set<String> errors, GenericKafkaListener listener) {
        if (KafkaListenerType.INTERNAL != listener.getType()
                && listener.getConfiguration().getUseServiceDnsDomain() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure useServiceDnsDomain because it is not internal listener");
        }
    }

    /**
     * Validates that ingressClass is used only with Ingress type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateIngressClass(Set<String> errors, GenericKafkaListener listener) {
        if (!KafkaListenerType.INGRESS.equals(listener.getType())
                && listener.getConfiguration().getIngressClass() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure ingressClass because it is not Ingress based listener");
        }
    }

    /**
     * Validates that preferredAddressType is used only with NodePort type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validatePreferredAddressType(Set<String> errors, GenericKafkaListener listener) {
        if (!KafkaListenerType.NODEPORT.equals(listener.getType())
                && listener.getConfiguration().getPreferredNodePortAddressType() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure preferredAddressType because it is not NodePort based listener");
        }
    }

    /**
     * Validates that externalTrafficPolicy is used only with Loadbalancer type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateExternalTrafficPolicy(Set<String> errors, GenericKafkaListener listener) {
        if (!(KafkaListenerType.LOADBALANCER.equals(listener.getType()) || KafkaListenerType.NODEPORT.equals(listener.getType()))
                && listener.getConfiguration().getExternalTrafficPolicy() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure externalTrafficPolicy because it is not LoadBalancer or NodePort based listener");
        }
    }

    /**
     * Validates that loadBalancerSourceRanges is used only with Loadbalancer type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateLoadBalancerSourceRanges(Set<String> errors, GenericKafkaListener listener) {
        if (!KafkaListenerType.LOADBALANCER.equals(listener.getType())
                && listener.getConfiguration().getLoadBalancerSourceRanges() != null
                && !listener.getConfiguration().getLoadBalancerSourceRanges().isEmpty())    {
            errors.add("listener " + listener.getName() + " cannot configure loadBalancerSourceRanges because it is not LoadBalancer based listener");
        }
    }

    /**
     * Validates that bootstrap.host is used only with Route or Ingress type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateBootstrapHost(Set<String> errors, GenericKafkaListener listener) {
        if ((!KafkaListenerType.ROUTE.equals(listener.getType()) && !KafkaListenerType.INGRESS.equals(listener.getType()))
                && listener.getConfiguration().getBootstrap().getHost() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure bootstrap.host because it is not Route ot Ingress based listener");
        }
    }

    /**
     * Validates that bootstrap.loadBalancerIP is used only with NodePort type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateBootstrapLoadBalancerIp(Set<String> errors, GenericKafkaListener listener) {
        if (!KafkaListenerType.LOADBALANCER.equals(listener.getType())
                && listener.getConfiguration().getBootstrap().getLoadBalancerIP() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure bootstrap.loadBalancerIP because it is not LoadBalancer based listener");
        }
    }

    /**
     * Validates that bootstrap.nodePort is used only with NodePort type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateBootstrapNodePort(Set<String> errors, GenericKafkaListener listener) {
        if (!KafkaListenerType.NODEPORT.equals(listener.getType())
                && listener.getConfiguration().getBootstrap().getNodePort() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure bootstrap.nodePort because it is not NodePort based listener");
        }
    }

    /**
     * Validates that bootstrap.dnsAnnotations is used only with LoadBalancer, NodePort or Ingress type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateBootstrapDnsAnnotations(Set<String> errors, GenericKafkaListener listener) {
        if ((!KafkaListenerType.LOADBALANCER.equals(listener.getType()) && !KafkaListenerType.NODEPORT.equals(listener.getType()) && !KafkaListenerType.INGRESS.equals(listener.getType()))
                && listener.getConfiguration().getBootstrap().getAnnotations() != null
                && !listener.getConfiguration().getBootstrap().getAnnotations().isEmpty())    {
            errors.add("listener " + listener.getName() + " cannot configure bootstrap.dnsAnnotations because it is not LoadBalancer, NodePort or Ingress based listener");
        }
    }

    /**
     * Validates that brokers[].host is used only with Route or Ingress type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     * @param broker    Broker configuration which needs to be validated
     */
    private static void validateBrokerHost(Set<String> errors, GenericKafkaListener listener, GenericKafkaListenerConfigurationBroker broker) {
        if ((!KafkaListenerType.ROUTE.equals(listener.getType()) && !KafkaListenerType.INGRESS.equals(listener.getType()))
                && broker.getHost() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure brokers[].host because it is not Route ot Ingress based listener");
        }
    }

    /**
     * Validates that brokers[].loadBalancerIP is used only with NodePort type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     * @param broker    Broker configuration which needs to be validated
     */
    private static void validateBrokerLoadBalancerIp(Set<String> errors, GenericKafkaListener listener, GenericKafkaListenerConfigurationBroker broker) {
        if (!KafkaListenerType.LOADBALANCER.equals(listener.getType())
                && broker.getLoadBalancerIP() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure brokers[].loadBalancerIP because it is not LoadBalancer based listener");
        }
    }

    /**
     * Validates that brokers[].nodePort is used only with NodePort type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     * @param broker    Broker configuration which needs to be validated
     */
    private static void validateBrokerNodePort(Set<String> errors, GenericKafkaListener listener, GenericKafkaListenerConfigurationBroker broker) {
        if (!KafkaListenerType.NODEPORT.equals(listener.getType())
                && broker.getNodePort() != null)    {
            errors.add("listener " + listener.getName() + " cannot configure brokers[].nodePort because it is not NodePort based listener");
        }
    }

    /**
     * Validates that brokers[].dnsAnnotations is used only with LoadBalancer, NodePort or Ingress type listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     * @param broker    Broker configuration which needs to be validated
     */
    private static void validateBrokerDnsAnnotations(Set<String> errors, GenericKafkaListener listener, GenericKafkaListenerConfigurationBroker broker) {
        if ((!KafkaListenerType.LOADBALANCER.equals(listener.getType()) && !KafkaListenerType.NODEPORT.equals(listener.getType()) && !KafkaListenerType.INGRESS.equals(listener.getType()))
                && broker.getAnnotations() != null
                && !broker.getAnnotations().isEmpty())    {
            errors.add("listener " + listener.getName() + " cannot configure brokers[].dnsAnnotations because it is not LoadBalancer, NodePort or Ingress based listener");
        }
    }

    /**
     * Validates that Route and Ingress listeners have always enabled TLS
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateRouteAndIngressTlsOnly(Set<String> errors, GenericKafkaListener listener) {
        if ((KafkaListenerType.ROUTE.equals(listener.getType()) || KafkaListenerType.INGRESS.equals(listener.getType()))
                && !listener.isTls())    {
            errors.add("listener " + listener.getName() + " is Route or Ingress type listener and requires enabled TLS encryption");
        }
    }

    /**
     * Validates whether any features depending on TLS are not configured on non-TLS listener
     *
     * @param errors    List where any found errors will be added
     * @param listener  Listener which needs to be validated
     */
    private static void validateTlsFeaturesOnNonTlsListener(Set<String> errors, GenericKafkaListener listener)    {
        if (!listener.isTls())  {
            if (listener.getAuth() != null
                    && KafkaListenerAuthenticationTls.TYPE_TLS.equals(listener.getAuth().getType())) {
                errors.add("listener " + listener.getName() + " cannot use " + KafkaListenerAuthenticationTls.TYPE_TLS + " type authentication with disabled TLS encryption");
            }

            if (listener.getConfiguration() != null
                    && listener.getConfiguration().getBrokerCertChainAndKey() != null)  {
                errors.add("listener " + listener.getName() + " cannot configure custom TLS certificate with disabled TLS encryption");
            }
        }
    }

    /**
     * Validates provided OAuth configuration. Throws InvalidResourceException when OAuth configuration contains forbidden combinations.
     *
     * @param errors    List where any found errors will be added
     * @param listener  The listener where OAuth authentication should be validated
     */
    @SuppressWarnings({"checkstyle:BooleanExpressionComplexity", "checkstyle:NPathComplexity", "checkstyle:CyclomaticComplexity"})
    private static void validateOauth(Set<String> errors, GenericKafkaListener listener) {
        if (listener.getAuth() != null
                && KafkaListenerAuthenticationOAuth.TYPE_OAUTH.equals(listener.getAuth().getType())) {
            KafkaListenerAuthenticationOAuth oAuth = (KafkaListenerAuthenticationOAuth) listener.getAuth();
            String listenerName = listener.getName();

            boolean hasJwksRefreshSecondsValidInput = oAuth.getJwksRefreshSeconds() != null && oAuth.getJwksRefreshSeconds() > 0;
            boolean hasJwksExpirySecondsValidInput = oAuth.getJwksExpirySeconds() != null && oAuth.getJwksExpirySeconds() > 0;
            boolean hasJwksMinRefreshPauseSecondsValidInput = oAuth.getJwksMinRefreshPauseSeconds() != null && oAuth.getJwksMinRefreshPauseSeconds() >= 0;

            if (oAuth.getIntrospectionEndpointUri() == null && oAuth.getJwksEndpointUri() == null) {
                errors.add("listener " + listenerName + ": Introspection endpoint URI or JWKS endpoint URI has to be specified");
            }

            if (oAuth.getValidIssuerUri() == null && oAuth.isCheckIssuer()) {
                errors.add("listener " + listenerName + ": Valid Issuer URI has to be specified or 'checkIssuer' set to false");
            }

            if (oAuth.getIntrospectionEndpointUri() != null && (oAuth.getClientId() == null || oAuth.getClientSecret() == null)) {
                errors.add("listener " + listenerName + ": Introspection Endpoint URI needs to be configured together with clientId and clientSecret");
            }

            if (oAuth.getUserInfoEndpointUri() != null && oAuth.getIntrospectionEndpointUri() == null) {
                errors.add("listener " + listenerName + ": User Info Endpoint URI can only be used if Introspection Endpoint URI is also configured");
            }

            if (oAuth.getJwksEndpointUri() == null && (hasJwksRefreshSecondsValidInput || hasJwksExpirySecondsValidInput || hasJwksMinRefreshPauseSecondsValidInput)) {
                errors.add("listener " + listenerName + ": jwksRefreshSeconds, jwksExpirySeconds and jwksMinRefreshPauseSeconds can only be used together with jwksEndpointUri");
            }

            if (oAuth.getJwksRefreshSeconds() != null && !hasJwksRefreshSecondsValidInput) {
                errors.add("listener " + listenerName + ": jwksRefreshSeconds needs to be a positive integer (set to: " + oAuth.getJwksRefreshSeconds() + ")");
            }

            if (oAuth.getJwksExpirySeconds() != null && !hasJwksExpirySecondsValidInput) {
                errors.add("listener " + listenerName + ": jwksExpirySeconds needs to be a positive integer (set to: " + oAuth.getJwksExpirySeconds() + ")");
            }

            if (oAuth.getJwksMinRefreshPauseSeconds() != null && !hasJwksMinRefreshPauseSecondsValidInput) {
                errors.add("listener " + listenerName + ": jwksMinRefreshPauseSeconds needs to be a positive integer or zero (set to: " + oAuth.getJwksMinRefreshPauseSeconds() + ")");
            }

            if ((hasJwksExpirySecondsValidInput && hasJwksRefreshSecondsValidInput && oAuth.getJwksExpirySeconds() < oAuth.getJwksRefreshSeconds() + 60) ||
                    (!hasJwksExpirySecondsValidInput && hasJwksRefreshSecondsValidInput && KafkaListenerAuthenticationOAuth.DEFAULT_JWKS_EXPIRY_SECONDS < oAuth.getJwksRefreshSeconds() + 60) ||
                    (hasJwksExpirySecondsValidInput && !hasJwksRefreshSecondsValidInput && oAuth.getJwksExpirySeconds() < KafkaListenerAuthenticationOAuth.DEFAULT_JWKS_REFRESH_SECONDS + 60)) {
                errors.add("listener " + listenerName + ": The refresh interval has to be at least 60 seconds shorter then the expiry interval specified in `jwksExpirySeconds`");
            }

            if (!oAuth.isAccessTokenIsJwt()) {
                if (oAuth.getJwksEndpointUri() != null) {
                    errors.add("listener " + listenerName + ": accessTokenIsJwt=false can not be used together with jwksEndpointUri");
                }
                if (!oAuth.isCheckAccessTokenType()) {
                    errors.add("listener " + listenerName + ": checkAccessTokenType can not be set to false when accessTokenIsJwt is false");
                }
            }

            if (!oAuth.isCheckAccessTokenType() && oAuth.getIntrospectionEndpointUri() != null) {
                errors.add("listener " + listenerName + ": checkAccessTokenType=false can not be used together with introspectionEndpointUri");
            }

            if (oAuth.getValidTokenType() != null && oAuth.getIntrospectionEndpointUri() == null) {
                errors.add("listener " + listenerName + ": validTokenType can only be used with introspectionEndpointUri");
            }
        }
    }

    /**
     * Extracts all distinct port numbers from the listeners
     *
     * @param listeners List of all listeners
     * @return          List of used port numbers
     */
    private static List<Integer> getPorts(List<GenericKafkaListener> listeners)    {
        return listeners.stream().map(GenericKafkaListener::getPort).distinct().collect(Collectors.toList());
    }

    /**
     * Extracts all distinct listener names from the listeners
     *
     * @param listeners List of all listeners
     * @return          List of used names
     */
    private static List<String> getNames(List<GenericKafkaListener> listeners)    {
        return listeners.stream().map(GenericKafkaListener::getName).distinct().collect(Collectors.toList());
    }
}

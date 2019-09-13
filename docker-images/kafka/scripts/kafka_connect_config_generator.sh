#!/usr/bin/env bash

SECURITY_PROTOCOL=PLAINTEXT

if [ "$KAFKA_CONNECT_TLS" = "true" ]; then
    SECURITY_PROTOCOL="SSL"

    if [ -n "$KAFKA_CONNECT_TRUSTED_CERTS" ]; then
        TLS_CONFIGURATION=$(cat <<EOF
# TLS / SSL
ssl.truststore.location=/tmp/kafka/cluster.truststore.p12
ssl.truststore.password=${CERTS_STORE_PASSWORD}
ssl.truststore.type=PKCS12

producer.ssl.truststore.location=/tmp/kafka/cluster.truststore.p12
producer.ssl.truststore.password=${CERTS_STORE_PASSWORD}

consumer.ssl.truststore.location=/tmp/kafka/cluster.truststore.p12
consumer.ssl.truststore.password=${CERTS_STORE_PASSWORD}
EOF
)
    fi

    if [ -n "$KAFKA_CONNECT_TLS_AUTH_CERT" ] && [ -n "$KAFKA_CONNECT_TLS_AUTH_KEY" ]; then
        TLS_AUTH_CONFIGURATION=$(cat <<EOF
ssl.keystore.location=/tmp/kafka/cluster.keystore.p12
ssl.keystore.password=${CERTS_STORE_PASSWORD}
ssl.keystore.type=PKCS12

producer.ssl.keystore.location=/tmp/kafka/cluster.keystore.p12
producer.ssl.keystore.password=${CERTS_STORE_PASSWORD}
producer.ssl.keystore.type=PKCS12

consumer.ssl.keystore.location=/tmp/kafka/cluster.keystore.p12
consumer.ssl.keystore.password=${CERTS_STORE_PASSWORD}
consumer.ssl.keystore.type=PKCS12
EOF
)
    fi
fi

if [ -n "$KAFKA_CONNECT_SASL_MECHANISM" ]; then
    if [ "$SECURITY_PROTOCOL" = "SSL" ]; then
        SECURITY_PROTOCOL="SASL_SSL"
    else
        SECURITY_PROTOCOL="SASL_PLAINTEXT"
    fi
    
    PASSWORD=$(cat /opt/kafka/connect-password/$KAFKA_CONNECT_SASL_PASSWORD_FILE)

    if [ "x$KAFKA_CONNECT_SASL_MECHANISM" = "xplain" ]; then
        SASL_MECHANISM="PLAIN"
        JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${KAFKA_CONNECT_SASL_USERNAME}\" password=\"${PASSWORD}\";"
    elif [ "x$KAFKA_CONNECT_SASL_MECHANISM" = "xscram-sha-512" ]; then
        SASL_MECHANISM="SCRAM-SHA-512"
        JAAS_CONFIG="org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${KAFKA_CONNECT_SASL_USERNAME}\" password=\"${PASSWORD}\";"
    elif [ "x$KAFKA_CONNECT_SASL_MECHANISM" = "xoauth" ]; then
        if [ ! -z "$KAFKA_CONNECT_OAUTH_ACCESS_TOKEN" ]; then
            OAUTH_ACCESS_TOKEN="oauth.access.token=\"$KAFKA_CONNECT_OAUTH_ACCESS_TOKEN\""
        fi

        if [ ! -z "$KAFKA_CONNECT_OAUTH_REFRESH_TOKEN" ]; then
            OAUTH_REFRESH_TOKEN="oauth.refresh.token=\"$KAFKA_CONNECT_OAUTH_REFRESH_TOKEN\""
        fi

        if [ ! -z "$KAFKA_CONNECT_OAUTH_CLIENT_SECRET" ]; then
            OAUTH_CLIENT_SECRET="oauth.client.secret=\"$KAFKA_CONNECT_OAUTH_CLIENT_SECRET\""
        fi

        SASL_MECHANISM="OAUTHBEARER"
        JAAS_CONFIG="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required ${KAFKA_CONNECT_OAUTH_CONFIG} ${OAUTH_CLIENT_SECRET} ${OAUTH_REFRESH_TOKEN} ${OAUTH_ACCESS_TOKEN};"
        OAUTH_CALLBACK_CLASS="io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler"
    fi

    SASL_AUTH_CONFIGURATION=$(cat <<EOF
sasl.mechanism=${SASL_MECHANISM}
sasl.jaas.config=${JAAS_CONFIG}
sasl.login.callback.handler.class=${OAUTH_CALLBACK_CLASS}

producer.sasl.mechanism=${SASL_MECHANISM}
producer.sasl.jaas.config=${JAAS_CONFIG}
producer.sasl.login.callback.handler.class=${OAUTH_CALLBACK_CLASS}

consumer.sasl.mechanism=${SASL_MECHANISM}
consumer.sasl.jaas.config=${JAAS_CONFIG}
consumer.sasl.login.callback.handler.class=${OAUTH_CALLBACK_CLASS}

EOF
)
fi

# Write the config file
cat <<EOF
# Bootstrap servers
bootstrap.servers=${KAFKA_CONNECT_BOOTSTRAP_SERVERS}
# REST Listeners
rest.port=8083
rest.advertised.host.name=$(hostname -I)
rest.advertised.port=8083
# Plugins
plugin.path=${KAFKA_CONNECT_PLUGIN_PATH}
# Provided configuration
${KAFKA_CONNECT_CONFIGURATION}

security.protocol=${SECURITY_PROTOCOL}
producer.security.protocol=${SECURITY_PROTOCOL}
consumer.security.protocol=${SECURITY_PROTOCOL}
${TLS_CONFIGURATION}
${TLS_AUTH_CONFIGURATION}
${SASL_AUTH_CONFIGURATION}
EOF
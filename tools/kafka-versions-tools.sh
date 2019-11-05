#!/usr/bin/env bash
set -e

VERSIONS_FILE="$(dirname $(realpath $0))/../kafka-versions.yaml"

# Gets the default Kafka version and sets "default_kafka_version" variable
# to the corresponding version string.
function get_default_kafka_version {

    finished=0
    counter=0
    while [ $finished -lt 1 ] 
    do
        version="$(yq read $VERSIONS_FILE [${counter}].version)"

        if [ "$version" = "null" ]
        then
            finished=1
        else
            if [ "$(yq read $VERSIONS_FILE [${counter}].default)" = "true" ]
            then
                default_kafka_version=$version
                finished=1
            fi
            counter=$((counter+1))
        fi
    done

    unset finished
    unset counter
    unset version

}

function get_kafka_versions {
    eval versions="$(yq read $VERSIONS_FILE '*.version' -j | tr '[],' '() ')"
}

function get_kafka_urls {
    eval binary_urls="$(yq read $VERSIONS_FILE '*.url' -j | tr '[],' '() ')"
}

function get_kafka_checksums {
    eval checksums="$(yq read $VERSIONS_FILE '*.checksum' -j | tr '[],' '() ')"
}

function get_kafka_third_party_libs {
    eval libs="$(yq read "$VERSIONS_FILE" '*.third-party-libs' -j | tr '[],' '() ')"
}

function get_kafka_protocols {
    eval protocols="$(yq read $VERSIONS_FILE '*.protocol' -j | tr '[],' '() ')"
}

function get_kafka_formats {
    eval formats="$(yq read $VERSIONS_FILE '*.format' -j | tr '[],' '() ')"
}

# Parses the Kafka versions file and creates three associative arrays:
# "version_binary_urls": Maps from version string to url from which the kafka source 
# tar will be downloaded.
# "version_checksums": Maps from version string to sha512 checksum.
# "version_libs": Maps from version string to third party library version string.
function get_version_maps {

    get_kafka_versions
    get_kafka_urls
    get_kafka_checksums
    get_kafka_third_party_libs

    declare -Ag version_binary_urls
    declare -Ag version_checksums
    declare -Ag version_libs

    for i in "${!versions[@]}"
    do 
        version_binary_urls[${versions[$i]}]=${binary_urls[$i]}
        version_checksums[${versions[$i]}]=${checksums[$i]}
        version_libs[${versions[$i]}]=${libs[$i]}
    done
    
}

#!/usr/bin/env bash

export GPG_TTY=$(tty)

echo $GPG_SIGNING_KEY | base64 -d > signing.gpg
gpg --batch --import signing.gpg

GPG_EXECUTABLE=gpg mvn -B $MVN_ARGS -DskipTests -s ./.azure/scripts/settings.xml  -pl ./,crd-generator,api,test-container -P ossrh package gpg:sign deploy

rm -rf signing.gpg
gpg --delete-keys
gpg --delete-secret-keys
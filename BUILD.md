# Building barnabas

Barnabas is using `make` as its main build system. Our make build supports several different targets 
mainly for building and pushing docker images.

<!-- TOC depthFrom:2 -->

- [Docker images](#docker-images)
    - [Building Docker images](#building-docker-images)
    - [Tagging and pushing Docker images](#tagging-and-pushing-docker-images)
- [Building everything](#building-everything)
- [Release](#release)

<!-- /TOC -->

## Docker images

### Building Docker images

The `docker_build` target will build the Docker images provided by the Barnabas project. You can build 
all Barnabas Docker images by calling `make docker_build` from the root of the Barnabas repository. Or 
you can build an individual Docker image by running `make docker_build` from the subdirectories with 
their respective Dockerfiles - e.g. `kafka_base`, `kafka_statefulsets` etc.

The `docker_build` target will always build the images under the `enmasseproject` organization. This is 
necessary in order to be able to reuse the base image you might have just built without modifying all Dockerfiles.

To configure the `docker_build` target you can set environment variables:
* `DOCKER_ORG` should contain the Docker organization for tagging/pushing the images (default is your username)
* `DOCKER_TAG` configured Docker tag (default is `latest`)

### Tagging and pushing Docker images

Target `docker_tag` can be used to tag the Docker images built by the `docker_build` target. This target 
is automatically called by the `docker_push` target and doesn't have to be called separately. `docker_push` 
target will login to the configured container repository and push the images.

To configure the `docker_tag` and `docker_push` targets you can set following environment variables:
* `DOCKER_ORG` configures the Docker organization for tagging/pushing the images (defaults to the value 
of the `$USER` environment variable)
* `DOCKER_TAG` configured Docker tag (default is `latest`)
* `DOCKER_REGISTRY` configures the Docker registry where the image will be pushed (default is `docker.io`)

## Building everything

`make all` command can be used to triger all the tasks above - build the Docker images, tag them and push 
them to the configured repository.

## Release

`make release` target can be used to create a release. Environment variable `RELEASE_VERSION` (default 
value `latest`) can be used to define the release version. The `release` target will create an archive 
with the Kubernetes and OpenShift YAML files which can be used for deployment. It will not build the Docker 
images - they should be built and pushed automatically by Travis CI when the release is tagged in the GitHub 
repsoitory.
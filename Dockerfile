#
# Docker image for cross-platform compilation
#

#
# Build stage
#

FROM alpine:3.10 as build

ARG BINTRAY_USERNAME
ARG BINTRAY_API

RUN apk add --no-cache openjdk8 python curl

ENV LANG       C.UTF-8
ENV JAVA_HOME  /usr/lib/jvm/java-1.8-openjdk
ENV PATH       $PATH:$JAVA_HOME/jre/bin:$JAVA_HOME/bin:/seed/bloop

COPY build.sh build.sbt BLOOP SEED COURSIER  /seed/
COPY project/  /seed/project/
COPY src/      /seed/src/

WORKDIR /seed

RUN curl -L https://github.com/scalacenter/bloop/releases/download/v$(cat BLOOP)/install.py | python - -d bloop

# Pre-fetch bridges and their dependencies to speed up dependency resolution
# later
RUN blp-coursier fetch \
    ch.epfl.scala:bloop-js-bridge-0-6_2.12:$(cat BLOOP) \
    ch.epfl.scala:bloop-js-bridge-1-0_2.12:$(cat BLOOP) \
    ch.epfl.scala:bloop-native-bridge_2.12:$(cat BLOOP)

RUN set -x && \
    ./build.sh && \
    COURSIER_SBT_LAUNCHER_ADD_PLUGIN=true BINTRAY_USER=$BINTRAY_USERNAME BINTRAY_PASS=$BINTRAY_API ./csbt "; publishLocal; publish"

RUN blp-coursier bootstrap tindzk:seed_2.12:$(cat SEED) -f -o seed

#
# Run stage
#

FROM alpine:3.10

# Python is needed for the Bloop front-end
# Node.js is needed for running Scala.js programs
# Clang, libc-dev, libunwind-dev and g++ are needed for linking Scala Native
# programs
RUN apk add --no-cache python openjdk8 nodejs clang libc-dev libunwind-dev g++

ENV LANG       C.UTF-8
ENV JAVA_HOME  /usr/lib/jvm/java-1.8-openjdk
ENV PATH       $PATH:$JAVA_HOME/jre/bin:$JAVA_HOME/bin

COPY --from=build /seed/seed              /usr/bin/
COPY --from=build /seed/bloop/            /usr/bin/
COPY --from=build /root/.ivy2/local/      /root/.ivy2/local/
COPY --from=build /root/.cache/coursier/  /root/.cache/coursier/
COPY etc/seed.toml                        /root/.config/seed.toml

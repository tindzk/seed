#
# Docker image for cross-platform compilation
#

#
# Build stage
#

# Coursier requires glibc
# See also https://github.com/coursier/coursier/issues/1739
FROM frolvlad/alpine-glibc:alpine-3.12 as build

ARG BINTRAY_USERNAME
ARG BINTRAY_API

# libstdc++ is needed for Coursier
RUN apk add --no-cache openjdk8 libstdc++

ENV LANG       C.UTF-8
ENV JAVA_HOME  /usr/lib/jvm/java-1.8-openjdk
ENV PATH       $PATH:$JAVA_HOME/jre/bin:$JAVA_HOME/bin:/seed/bin:/root/.local/share/coursier/bin

COPY build.sh build.sbt BLOOP SEED  /seed/
COPY project/   /seed/project/
COPY src/       /seed/src/
COPY scaladoc/  /seed/scaladoc/

WORKDIR /seed/bin

# Coursier v2.0.7
RUN wget https://raw.githubusercontent.com/coursier/launchers/cbccb1e174b641e7b8f1907bf4fbdfa6f3d58d6a/cs-x86_64-pc-linux && mv cs-x86_64-pc-linux coursier && chmod +x coursier
RUN /seed/bin/coursier install bloop:$(cat ../BLOOP) --only-prebuilt=true
RUN coursier install bloop:$(cat ../BLOOP) --only-prebuilt=true

# Pre-fetch bridges and their dependencies to speed up dependency resolution
# later
RUN coursier fetch \
    ch.epfl.scala:bloop-js-bridge-0-6_2.12:$(cat ../BLOOP) \
    ch.epfl.scala:bloop-js-bridge-1-0_2.12:$(cat ../BLOOP) \
    ch.epfl.scala:bloop-native-bridge-0-3_2.12:$(cat ../BLOOP) \
    ch.epfl.scala:bloop-native-bridge-0-4_2.12:$(cat ../BLOOP)

WORKDIR /seed

RUN set -x && \
    ./build.sh && \
    COURSIER_SBT_LAUNCHER_ADD_PLUGIN=true BINTRAY_USER=$BINTRAY_USERNAME BINTRAY_PASS=$BINTRAY_API ./csbt "; publishLocal; publish"

RUN coursier bootstrap tindzk:seed_2.12:$(cat SEED) -f -o seed

#
# Run stage
#

FROM frolvlad/alpine-glibc:alpine-3.12

# Node.js is needed for running Scala.js programs
# Clang, libc-dev, libunwind-dev and g++ are needed for linking Scala Native
# programs
RUN apk add --no-cache openjdk8 nodejs clang libc-dev libunwind-dev g++

ENV LANG       C.UTF-8
ENV JAVA_HOME  /usr/lib/jvm/java-1.8-openjdk
ENV PATH       $PATH:$JAVA_HOME/jre/bin:$JAVA_HOME/bin

COPY --from=build /seed/seed              /usr/bin/
COPY --from=build /seed/bin/              /usr/bin/
COPY --from=build /root/.local/share/coursier/bin /usr/bin/
COPY --from=build /root/.ivy2/local/      /root/.ivy2/local/
COPY --from=build /root/.cache/coursier/  /root/.cache/coursier/
COPY etc/seed.toml                        /root/.config/seed.toml

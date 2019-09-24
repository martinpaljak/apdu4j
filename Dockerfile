FROM maven:3.6.2-jdk-8 AS build
WORKDIR /build
# To overcome Maven deficiencies.
COPY pom.xml .
COPY core/pom.xml ./core/
COPY pcsc/pom.xml ./pcsc/
COPY tool/pom.xml ./tool/
COPY testing/pom.xml ./testing/
# We only want to fetch dependencies, so don't run anything that is needed for a normal "package" (2x skip)
RUN mvn -Dexec.skip=true -Dmaven.gitcommitid.skip=true -e -B package dependency:go-offline
FROM build
COPY ./ .
RUN mvn -e -B verify
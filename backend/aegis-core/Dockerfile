# ---- build stage: compiles the app in a Maven container (no host Maven needed) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# SBOM generation tooling (feature: auto-generate a service's SBOM from a Git repo):
#   - git:  shallow-clone the repository
#   - syft: a STATIC SBOM cataloguer — it reads lockfiles/manifests and never runs a package manager
#           or any of the repo's own code, so cloning + scanning does not execute untrusted code.
# Installed over HTTPS from Anchore's official installer; pin a version there for reproducible builds.
# ca-certificates stays for git's HTTPS clone; curl is removed after use.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates curl \
 && curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin \
 && apt-get purge -y curl && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/* \
 && git --version && syft version
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:11-jre-jammy

ARG DATOMIC_VERSION=1.0.7556

RUN apt-get update && apt-get install -y curl unzip && rm -rf /var/lib/apt/lists/*

RUN curl -sSL -o /tmp/datomic.zip \
    https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip \
    && unzip -q /tmp/datomic.zip -d /opt \
    && mv /opt/datomic-pro-${DATOMIC_VERSION} /opt/datomic-pro \
    && rm /tmp/datomic.zip

WORKDIR /opt/datomic-pro

EXPOSE 4334 4335

ENTRYPOINT ["bin/transactor"]
CMD ["config/transactor.properties"]

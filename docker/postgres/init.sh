#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER ${DATOMIC_DB_USER:-datomic} WITH PASSWORD '${DATOMIC_DB_PASSWORD:-datomic}';
    CREATE DATABASE ${DATOMIC_DB_USER:-datomic} OWNER ${DATOMIC_DB_USER:-datomic};
EOSQL

psql -v ON_ERROR_STOP=1 --username "${DATOMIC_DB_USER:-datomic}" --dbname "${DATOMIC_DB_USER:-datomic}" <<-EOSQL
    CREATE TABLE datomic_kvs (
        id text NOT NULL,
        rev integer,
        map text,
        val bytea,
        CONSTRAINT pk_id PRIMARY KEY (id)
    );
EOSQL

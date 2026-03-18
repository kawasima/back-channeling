#!/bin/bash
set -e

DB_USER="${DATOMIC_DB_USER:-datomic}"
DB_PASSWORD="${DATOMIC_DB_PASSWORD:-datomic}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db_user="$DB_USER" -v db_password="$DB_PASSWORD" <<-'EOSQL'
    CREATE USER :db_user WITH PASSWORD :'db_password';
    CREATE DATABASE :db_user OWNER :db_user;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$DB_USER" --dbname "$DB_USER" <<-'EOSQL'
    CREATE TABLE datomic_kvs (
        id text NOT NULL,
        rev integer,
        map text,
        val bytea,
        CONSTRAINT pk_id PRIMARY KEY (id)
    );
EOSQL

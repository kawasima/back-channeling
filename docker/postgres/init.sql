-- Datomic On-Prem PostgreSQL storage setup
-- Equivalent to bin/sql/postgres-user.sql + postgres-db.sql + postgres-table.sql

CREATE USER datomic WITH PASSWORD 'datomic';
CREATE DATABASE datomic OWNER datomic;

\connect datomic

CREATE TABLE datomic_kvs (
    id text NOT NULL,
    rev integer,
    map text,
    val bytea,
    CONSTRAINT pk_id PRIMARY KEY (id)
);

ALTER TABLE datomic_kvs OWNER TO datomic;
GRANT ALL ON TABLE datomic_kvs TO datomic;

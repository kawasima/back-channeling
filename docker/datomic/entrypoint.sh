#!/bin/bash
set -e

# Expand environment variables in transactor.properties template
envsubst '$DATOMIC_DB_USER $DATOMIC_DB_PASSWORD' < config/transactor.properties.tmpl > config/transactor.properties

exec bin/transactor config/transactor.properties

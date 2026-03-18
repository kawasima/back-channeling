#!/bin/bash
set -e

# Expand environment variables in transactor.properties template
envsubst < config/transactor.properties.tmpl > config/transactor.properties

exec bin/transactor config/transactor.properties

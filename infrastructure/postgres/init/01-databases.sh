#!/bin/bash
# Crea una base y un usuario por componente. Se ejecuta solo la primera vez
# que arranca el volumen de PostgreSQL.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
  CREATE ROLE helpdesk    LOGIN PASSWORD '${HELPDESK_DB_PASSWORD}';
  CREATE ROLE airflow     LOGIN PASSWORD '${AIRFLOW_DB_PASSWORD}';
  CREATE ROLE lake        LOGIN PASSWORD '${LAKE_DB_PASSWORD}';
  CREATE ROLE lake_reader LOGIN PASSWORD '${LAKE_READER_PASSWORD}';

  CREATE DATABASE helpdesk     OWNER helpdesk;
  CREATE DATABASE airflow      OWNER airflow;
  CREATE DATABASE lake_catalog OWNER lake;

  REVOKE ALL ON DATABASE helpdesk FROM PUBLIC;
  GRANT CONNECT ON DATABASE helpdesk TO lake_reader;
EOSQL

# lake_reader: solo lectura sobre las tablas que cree el usuario helpdesk.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname helpdesk <<-EOSQL
  GRANT USAGE ON SCHEMA public TO lake_reader;
  ALTER DEFAULT PRIVILEGES FOR ROLE helpdesk IN SCHEMA public
        GRANT SELECT ON TABLES TO lake_reader;
EOSQL

# PostgreSQL 15+ ya no da CREATE en public a todos; airflow y lake son dueños de su base.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname airflow -c "GRANT ALL ON SCHEMA public TO airflow;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname lake_catalog -c "GRANT ALL ON SCHEMA public TO lake;"

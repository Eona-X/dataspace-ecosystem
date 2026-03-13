#!/bin/bash
set -e

echo "Create user $DB_USER (if not exists)"
psql -v ON_ERROR_STOP=1 -h $DB_FQDN --username $POSTGRES_USER --dbname $POSTGRES_DB <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$DB_USER') THEN
      CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';
    ELSE
      RAISE NOTICE 'User $DB_USER already exists, skipping';
    END IF;
  END
  \$\$;
EOSQL

echo "Create DB $DB_NAME (if not exists)"
psql -v ON_ERROR_STOP=1 -h $DB_FQDN --username $POSTGRES_USER --dbname $POSTGRES_DB <<-EOSQL
  SELECT 'CREATE DATABASE $DB_NAME OWNER $DB_USER'
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$DB_NAME')\gexec
EOSQL

echo "Grant access to $DB_USER"
psql -v ON_ERROR_STOP=1 -h $DB_FQDN --username $POSTGRES_USER --dbname $DB_NAME -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $DB_USER"

echo "DB init completed successfully"

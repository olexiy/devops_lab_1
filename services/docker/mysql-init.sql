-- MySQL initialisation script for the local Docker Compose stack.
-- Runs automatically on first container start (docker-entrypoint-initdb.d/).
-- customers_db is created by the MYSQL_DATABASE env var; only the other two need explicit CREATE.

CREATE DATABASE IF NOT EXISTS accounts_db;
CREATE DATABASE IF NOT EXISTS transactions_db;

GRANT ALL PRIVILEGES ON customers_db.*     TO 'appuser'@'%';
GRANT ALL PRIVILEGES ON accounts_db.*      TO 'appuser'@'%';
GRANT ALL PRIVILEGES ON transactions_db.*  TO 'appuser'@'%';

FLUSH PRIVILEGES;

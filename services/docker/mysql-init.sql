-- Init script: creates the additional databases that Docker doesn't auto-create.
-- MYSQL_DATABASE in docker-compose creates customers_db; this script adds the other two.

CREATE DATABASE IF NOT EXISTS accounts_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS transactions_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON accounts_db.*     TO 'appuser'@'%';
GRANT ALL PRIVILEGES ON transactions_db.* TO 'appuser'@'%';
FLUSH PRIVILEGES;


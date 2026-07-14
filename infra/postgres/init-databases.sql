-- Runs once on first container start (docker-entrypoint-initdb.d).
-- Creates one database per service, per the "database per service" principle.

CREATE DATABASE userdb;
CREATE DATABASE productdb;
CREATE DATABASE inventorydb;
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
CREATE DATABASE notificationdb;

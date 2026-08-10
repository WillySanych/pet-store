CREATE SCHEMA IF NOT EXISTS catalog      AUTHORIZATION petstore;
CREATE SCHEMA IF NOT EXISTS inventory    AUTHORIZATION petstore;
CREATE SCHEMA IF NOT EXISTS customer     AUTHORIZATION petstore;
CREATE SCHEMA IF NOT EXISTS orders       AUTHORIZATION petstore;
CREATE SCHEMA IF NOT EXISTS subscription AUTHORIZATION petstore;

COMMENT ON SCHEMA catalog      IS 'catalog-service';
COMMENT ON SCHEMA inventory    IS 'inventory-service';
COMMENT ON SCHEMA customer     IS 'customer-service';
COMMENT ON SCHEMA orders       IS 'order-service';
COMMENT ON SCHEMA subscription IS 'subscription-service';

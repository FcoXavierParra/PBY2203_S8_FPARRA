-- ---------------------------------------------------------------------------
-- Usuarios de aplicacion en la Autonomous Database, uno por microservicio.
-- ---------------------------------------------------------------------------
-- Se ejecuta UNA vez, conectado como ADMIN, desde Database Actions (SQL) o
-- SQL Developer. Las tres claves se piden al ejecutar (&clave_...): no quedan
-- escritas en este archivo, que se publica.
--
-- POR QUE NO ADMIN
-- Hasta la semana 7 los servicios se conectaban como ADMIN. En contenedores
-- con 'restart: on-failure', una clave vencida o mal escrita hace que el
-- servicio reintente el login en bucle; ADMIN se bloquea a los diez fallos y
-- es la unica cuenta que puede administrar la base. Un usuario por servicio
-- limita ese dano a un solo servicio y, ademas, a un solo esquema: ms-auditoria
-- no puede leer los saldos aunque quede comprometido.
--
-- UNA BASE LOGICA POR SERVICIO
-- Es el patron database-per-service dentro de una sola ADB Always Free:
--   BANK_CUENTAS  ms-cuentas (las dos instancias comparten este esquema: son
--                 el mismo servicio escalado)
--   BANK_TRANSF   ms-transferencias: cupos, transferencias, outbox
--   BANK_AUDIT    ms-auditoria: registro inmutable de eventos
--
-- Las claves deben cumplir la politica de ADB: 12 a 30 caracteres, con
-- mayuscula, minuscula y numero, sin comillas dobles y sin el nombre de usuario.
-- ---------------------------------------------------------------------------

-- Perfil propio: la clave no vence durante el ramo (evita el ORA-01017 de la
-- semana 5) y, tras 10 fallos, la cuenta se bloquea solo 5 minutos y se
-- desbloquea sola, en vez de quedar bloqueada hasta que un humano intervenga.
CREATE PROFILE bank_app LIMIT
    PASSWORD_LIFE_TIME     UNLIMITED
    FAILED_LOGIN_ATTEMPTS  10
    PASSWORD_LOCK_TIME     5/1440;

CREATE USER bank_cuentas IDENTIFIED BY "&clave_cuentas" PROFILE bank_app
    DEFAULT TABLESPACE data QUOTA UNLIMITED ON data;
CREATE USER bank_transf  IDENTIFIED BY "&clave_transf"  PROFILE bank_app
    DEFAULT TABLESPACE data QUOTA UNLIMITED ON data;
CREATE USER bank_audit   IDENTIFIED BY "&clave_audit"   PROFILE bank_app
    DEFAULT TABLESPACE data QUOTA UNLIMITED ON data;

-- Solo lo necesario para conectarse y ser duenos de sus tablas. CREATE
-- SEQUENCE lo exigen las columnas IDENTITY que Hibernate crea en auditoria.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO bank_cuentas;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO bank_transf;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO bank_audit;

-- ---------------------------------------------------------------------------
-- Datos de ms-cuentas: copia de lo que dejo el batch de la Experiencia 1
-- ---------------------------------------------------------------------------
-- Se COPIA y no se comparte: ms-cuentas modifica saldos, y las tablas de ADMIN
-- quedan intactas como el resultado original de la migracion. Si una corrida
-- deja los saldos en mal estado, se borra el esquema y se vuelve a copiar.
--
-- En operacion ms-cuentas solo actualiza 'cuenta' e inserta en
-- 'evento_procesado'; nunca inserta en transaccion ni movimiento_anual (lo
-- hace solo CargadorDatos, y solo con la tabla vacia). Por eso basta un
-- CREATE TABLE AS SELECT, aunque no copie la columna IDENTITY del original.
CREATE TABLE bank_cuentas.cuenta           AS SELECT * FROM admin.cuenta;
CREATE TABLE bank_cuentas.transaccion      AS SELECT * FROM admin.transaccion;
CREATE TABLE bank_cuentas.movimiento_anual AS SELECT * FROM admin.movimiento_anual;

-- CTAS no copia las restricciones. La clave primaria de cuenta es la que usa
-- el bloqueo pesimista (SELECT ... FOR UPDATE por cuenta_id): sin ella, cada
-- bloqueo recorreria la tabla entera.
ALTER TABLE bank_cuentas.cuenta           ADD CONSTRAINT pk_cuenta           PRIMARY KEY (cuenta_id);
ALTER TABLE bank_cuentas.transaccion      ADD CONSTRAINT pk_transaccion      PRIMARY KEY (id);
ALTER TABLE bank_cuentas.movimiento_anual ADD CONSTRAINT pk_movimiento_anual PRIMARY KEY (id);

-- Tabla de idempotencia de la semana 7 (misma definicion que
-- evento_procesado_oracle.sql), ahora en el esquema del servicio.
CREATE TABLE bank_cuentas.evento_procesado (
    evento_id        VARCHAR2(36)   NOT NULL,
    transferencia_id VARCHAR2(36)   NOT NULL,
    aplicada         NUMBER(1)      NOT NULL,
    motivo           VARCHAR2(255),
    saldo_origen     NUMBER(38,2),
    saldo_destino    NUMBER(38,2),
    respuesta_id     VARCHAR2(36)   NOT NULL,
    procesado_por    VARCHAR2(64),
    procesado_en     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_evento_procesado PRIMARY KEY (evento_id)
);

-- BANK_TRANSF y BANK_AUDIT parten vacios: sus tablas las crea Hibernate
-- (ddl-auto: update) en el primer arranque. Son de ese servicio y de nadie mas,
-- asi que no aplica la razon por la que ms-cuentas usa 'validate' (alli el
-- esquema lo definio el batch de la Experiencia 1).

-- ---------------------------------------------------------------------------
-- Verificacion
-- ---------------------------------------------------------------------------
SELECT username, profile, account_status FROM dba_users
 WHERE username IN ('BANK_CUENTAS', 'BANK_TRANSF', 'BANK_AUDIT') ORDER BY username;

SELECT 'cuenta' tabla, COUNT(*) filas FROM bank_cuentas.cuenta
UNION ALL SELECT 'transaccion',      COUNT(*) FROM bank_cuentas.transaccion
UNION ALL SELECT 'movimiento_anual', COUNT(*) FROM bank_cuentas.movimiento_anual;

-- Para rehacer desde cero:
--   DROP USER bank_cuentas CASCADE; DROP USER bank_transf CASCADE;
--   DROP USER bank_audit CASCADE;   DROP PROFILE bank_app;

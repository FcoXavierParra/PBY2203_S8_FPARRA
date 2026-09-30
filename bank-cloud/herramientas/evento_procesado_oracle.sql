-- ---------------------------------------------------------------------------
-- Tabla de idempotencia de ms-cuentas, para el perfil 'oracle'.
-- ---------------------------------------------------------------------------
-- Contra H2, Hibernate la crea sola (ddl-auto: update). Contra Oracle el perfil
-- usa ddl-auto: validate, porque el esquema lo manda la Experiencia 1 y este
-- proyecto no altera tablas ajenas; por eso la tabla nueva de esta semana se
-- crea a mano, una sola vez, antes de levantar con -Oracle.
--
-- Ver el javadoc de EventoProcesado en bank-core.
-- ---------------------------------------------------------------------------
CREATE TABLE evento_procesado (
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

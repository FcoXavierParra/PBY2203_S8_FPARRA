package cl.duoc.bank.contrato.eventos;

import java.time.Instant;

/**
 * Lo que tienen en comun los cuatro eventos de la saga.
 *
 * eventoId      identifica ESTE mensaje. Es la base de la deduplicacion: el
 *               broker lo usa como _AMQ_DUPL_ID y ms-cuentas lo guarda como
 *               clave primaria de los eventos ya procesados. Dos entregas del
 *               mismo evento tienen el mismo eventoId.
 * transferenciaId identifica la SAGA. Todos los eventos de una transferencia
 *               lo comparten, y es con lo que ms-auditoria reconstruye su
 *               historia completa.
 * ocurridoEn    cuando ocurrio el hecho, no cuando llego el mensaje.
 */
public interface EventoTransferencia {

    String eventoId();

    String transferenciaId();

    Instant ocurridoEn();
}

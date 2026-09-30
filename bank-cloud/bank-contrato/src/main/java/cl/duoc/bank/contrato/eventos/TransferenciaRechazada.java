package cl.duoc.bank.contrato.eventos;

import java.time.Instant;

/**
 * Paso 2, camino de compensacion. Lo publica ms-cuentas cuando la regla del
 * banco impide aplicar la transferencia: saldo insuficiente, o una de las dos
 * cuentas no existe.
 *
 * Es un HECHO DE NEGOCIO, no un error tecnico. El mensaje se proceso bien y
 * se confirma; lo que ocurrio es que la respuesta es "no". La distincion
 * decide a donde va: un rechazo sigue la saga y dispara la compensacion; una
 * excepcion al procesar hace rollback, se reintenta y, si persiste, termina en
 * la DLQ. Confundirlas mandaria a la DLQ a todo cliente sin saldo.
 */
public record TransferenciaRechazada(
        String eventoId,
        String transferenciaId,
        String causaEventoId,
        String motivo,
        String procesadoPor,
        Instant ocurridoEn) implements EventoTransferencia {
}

package cl.duoc.bank.contrato.eventos;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Paso 3. Lo publica ms-transferencias al cerrar la saga.
 *
 * @param estadoFinal  COMPLETADA o RECHAZADA
 * @param cupoLiberado el monto devuelto al cupo diario. Distinto de cero solo
 *                     cuando hubo compensacion: es la prueba, en el registro de
 *                     auditoria, de que el paso compensatorio se ejecuto.
 */
public record TransferenciaCerrada(
        String eventoId,
        String transferenciaId,
        String estadoFinal,
        String motivo,
        BigDecimal cupoLiberado,
        Instant ocurridoEn) implements EventoTransferencia {
}

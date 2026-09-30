package cl.duoc.bank.contrato;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * El estado de una transferencia, tal como lo informa ms-transferencias.
 *
 * @param estado PENDIENTE mientras la saga esta en curso; COMPLETADA o
 *               RECHAZADA al cerrar. No existe un estado "fallida": una
 *               transferencia que no se puede aplicar se rechaza con su
 *               motivo, y una que todavia no se aplico sigue pendiente.
 */
public record EstadoTransferencia(
        String transferenciaId,
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto,
        String estado,
        String motivo,
        Instant creadaEn,
        Instant actualizadaEn) {
}

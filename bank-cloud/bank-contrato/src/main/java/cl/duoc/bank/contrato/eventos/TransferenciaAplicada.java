package cl.duoc.bank.contrato.eventos;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Paso 2, camino feliz. Lo publica ms-cuentas despues de debitar el origen y
 * acreditar el destino en una sola transaccion local.
 *
 * @param causaEventoId el eventoId de la TransferenciaSolicitada que lo provoco.
 *                      Encadena causa y efecto en el registro de auditoria.
 * @param procesadoPor  la instancia de ms-cuentas que lo proceso, por ejemplo
 *                      "ms-cuentas:8093". Es lo que permite medir en la
 *                      evidencia como se reparte la carga entre instancias.
 */
public record TransferenciaAplicada(
        String eventoId,
        String transferenciaId,
        String causaEventoId,
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto,
        BigDecimal saldoOrigen,
        BigDecimal saldoDestino,
        String procesadoPor,
        Instant ocurridoEn) implements EventoTransferencia {
}

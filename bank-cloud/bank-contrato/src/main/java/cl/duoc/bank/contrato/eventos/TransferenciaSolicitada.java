package cl.duoc.bank.contrato.eventos;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Paso 1 de la saga. Lo publica ms-transferencias cuando acepto una
 * solicitud y ya reservo el cupo diario de la cuenta de origen.
 *
 * Lleva todo lo que ms-cuentas necesita para actuar sin volver a preguntar:
 * un evento que obliga al consumidor a hacer una llamada HTTP de vuelta para
 * enterarse de que se trata reintroduce el acoplamiento sincrono que la
 * mensajeria vino a quitar.
 */
public record TransferenciaSolicitada(
        String eventoId,
        String transferenciaId,
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto,
        Instant ocurridoEn) implements EventoTransferencia {
}

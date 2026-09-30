package cl.duoc.bank.contrato;

import java.math.BigDecimal;

/**
 * Resultado de un retiro.
 *
 * Un retiro rechazado NO es un error HTTP: es una respuesta 200 con autorizado
 * en false y el motivo. La diferencia importa para el Circuit Breaker del
 * cajero: "saldo insuficiente" es el servicio funcionando correctamente y no
 * debe contar como fallo, mientras que un timeout o un 500 si. Si se modelara
 * el rechazo como 4xx, unos cuantos clientes sin saldo abririan el circuito y
 * dejarian el cajero fuera de servicio.
 */
public record ResultadoRetiro(
        boolean autorizado,
        String motivo,
        BigDecimal saldoResultante) {
}

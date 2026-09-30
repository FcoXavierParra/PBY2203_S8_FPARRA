package cl.duoc.bank.contrato;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un movimiento de UNA cuenta, de la tabla MOVIMIENTO_ANUAL.
 *
 * No confundir con TransaccionBanco. La distincion importa y en la entrega
 * anterior costo una fuga de datos: MOVIMIENTO_ANUAL tiene columna cuenta_id y
 * por lo tanto se puede filtrar por titular; TRANSACCION no la tiene, es un
 * registro global del banco. El canal movil pedia "los movimientos de la cuenta
 * X" y recibia las ultimas transacciones del banco entero, que son de otros
 * clientes.
 */
public record MovimientoCuenta(
        Long cuentaId,
        LocalDate fecha,
        String tipoTransaccion,
        BigDecimal monto,
        String descripcion) {
}

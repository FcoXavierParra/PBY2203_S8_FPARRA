package cl.duoc.bank.contrato;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una transaccion del registro global del banco.
 *
 * La tabla TRANSACCION no tiene columna cuenta_id: es el libro de operaciones
 * del banco, no el extracto de un cliente. Eso tiene una consecuencia de
 * autorizacion que esta entrega respeta: consultarla es una atribucion de
 * ejecutivo, porque no existe forma de acotarla al titular que pregunta.
 */
public record TransaccionBanco(
        Long id,
        LocalDate fecha,
        BigDecimal monto,
        String tipo,
        boolean anomalia,
        String motivoAnomalia) {
}

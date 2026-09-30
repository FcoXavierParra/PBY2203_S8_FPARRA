package cl.duoc.bank.bff.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Transaccion con todo su detalle y las marcas que la interfaz web usa para
 * resaltarla: si supera el umbral de anomalia y si su tipo venia mal clasificado
 * en el origen.
 */
public record TransaccionWebDto(
        Long id,
        LocalDate fecha,
        BigDecimal monto,
        String montoFormateado,
        String tipo,
        boolean anomalia,
        boolean tipoDesconocido) {
}

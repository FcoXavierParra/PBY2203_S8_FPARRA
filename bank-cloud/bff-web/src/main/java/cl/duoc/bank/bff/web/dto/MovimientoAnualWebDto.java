package cl.duoc.bank.bff.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MovimientoAnualWebDto(
        LocalDate fecha,
        String transaccion,
        BigDecimal monto,
        String descripcion) {
}

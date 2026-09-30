package cl.duoc.bank.bff.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Estado de cuenta anual con su desglose completo. Solo el canal web lo expone. */
public record EstadoAnualWebDto(
        Long cuentaId,
        String titular,
        int anio,
        BigDecimal totalDepositos,
        BigDecimal totalRetiros,
        BigDecimal totalCompras,
        BigDecimal saldoNeto,
        int cantidadMovimientos,
        List<MovimientoAnualWebDto> movimientos) {
}

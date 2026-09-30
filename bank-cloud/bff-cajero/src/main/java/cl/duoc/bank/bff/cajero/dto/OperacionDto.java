package cl.duoc.bank.bff.cajero.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Consulta de saldo y retiro: las dos unicas operaciones del canal. */
public class OperacionDto {

    /**
     * Un solo campo.
     *
     * La ficha web de la misma cuenta trae doce y el resumen movil cuatro. Un
     * cajero automatico muestra una cifra en pantalla y no tiene donde poner
     * nada mas; enviarle el nombre del titular o su segmento etario seria
     * exponer datos personales en un equipo publico para no dibujarlos nunca.
     */
    public record Saldo(BigDecimal saldoDisponible, String moneda) {
    }

    public record SolicitudRetiro(
            @NotNull(message = "El monto es obligatorio")
            @Positive(message = "El monto debe ser mayor que cero")
            BigDecimal monto) {
    }

    /**
     * Resultado del retiro, con un codigo estable que el terminal usa para
     * decidir que mensaje mostrar sin tener que interpretar texto libre.
     */
    public record ResultadoRetiro(
            boolean autorizado,
            String codigo,
            String mensaje,
            BigDecimal montoEntregado,
            BigDecimal saldoRestante) {
    }
}

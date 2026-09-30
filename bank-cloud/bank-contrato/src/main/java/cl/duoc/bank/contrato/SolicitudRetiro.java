package cl.duoc.bank.contrato;

import java.math.BigDecimal;

/** Cuerpo de POST /interno/cuentas/{id}/retiro. */
public record SolicitudRetiro(BigDecimal monto) {
}

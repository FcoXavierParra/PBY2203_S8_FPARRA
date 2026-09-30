package cl.duoc.bank.bff.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/** Lo que el navegador manda y recibe al transferir. */
public final class TransferenciaWebDto {

    private TransferenciaWebDto() {
    }

    /**
     * Sin cuenta de origen, a proposito: el origen es la cuenta del titular y
     * sale del token. Si viniera en el cuerpo, el navegador podria escribir la
     * de otra persona. Es la misma leccion del IDOR de la semana 5, aplicada
     * a un endpoint que mueve dinero.
     */
    public record Solicitud(
            @NotNull Long cuentaDestino,
            @NotNull @Positive BigDecimal monto) {
    }

    /**
     * @param consultarEn donde preguntar como termino. La transferencia es
     *                    asincrona: al responder este 202 todavia no ocurrio.
     */
    public record Estado(
            String transferenciaId,
            String estado,
            Long cuentaDestino,
            BigDecimal monto,
            String motivo,
            Instant creadaEn,
            Instant actualizadaEn,
            String consultarEn) {
    }

    public record Rechazo(String codigo, String mensaje) {
    }
}

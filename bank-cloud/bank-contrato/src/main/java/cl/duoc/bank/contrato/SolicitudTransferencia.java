package cl.duoc.bank.contrato;

import java.math.BigDecimal;

/**
 * Lo que bff-web le manda a ms-transferencias por HTTP.
 *
 * La cuenta de origen viaja aqui porque ms-transferencias autentica
 * SERVICIOS, no personas: no sabe quien es el cliente. Quien garantiza que el
 * origen es la cuenta del titular -y no la que el navegador escribio en el
 * cuerpo- es bff-web, que la saca del token. Ver TransferenciaWebController.
 */
public record SolicitudTransferencia(
        Long cuentaOrigen,
        Long cuentaDestino,
        BigDecimal monto) {
}

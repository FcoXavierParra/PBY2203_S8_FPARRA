package cl.duoc.bank.bff.cajero.api;

import cl.duoc.bank.bff.cajero.dto.OperacionDto;
import cl.duoc.bank.cliente.CuentasNoDisponible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * La respuesta alternativa del canal cajero: fuera de servicio.
 *
 * ES LA MAS CONSERVADORA DE LAS TRES, Y TIENE QUE SERLO
 * =====================================================
 * El canal web muestra la pagina con un aviso y el movil le dice a la
 * aplicacion si conviene reintentar. Aqui hay una persona parada en la calle
 * con la tarjeta dentro de una maquina que entrega dinero, y la unica
 * respuesta honesta cuando no se sabe el estado de su cuenta es que la
 * operacion no se puede completar.
 *
 * Fallar de forma conservadora no es lo mismo que fallar peor. Es reconocer
 * que en este canal una respuesta equivocada cuesta dinero real, y que
 * "intente mas tarde" es preferible a un saldo dudoso o a un retiro cuyo
 * resultado se desconoce.
 *
 * EL MENSAJE CAMBIA SEGUN EL CIRCUITO, Y NO ES UN DETALLE
 * =======================================================
 * Con el circuito CERRADO fue un fallo suelto: puede ser transitorio y tiene
 * sentido decirle a la persona que reintente. Con el circuito ABIERTO el
 * canal viene fallando y reintentar solo la va a hacer esperar, asi que
 * corresponde decirle que se acerque a una sucursal.
 *
 * Y SI EL RETIRO YA SE HABIA APLICADO
 * ===================================
 * El codigo VERIFIQUE_SALDO existe para el caso en que la falla ocurrio
 * despues de que ms-cuentas descontara y antes de que la respuesta volviera.
 * El cajero no puede distinguirlo desde aqui, y por eso no reintenta ni afirma
 * que la operacion no se hizo: le pide a la persona que verifique. Ver el
 * javadoc de CajeroController sobre la clave de idempotencia que resolveria
 * esto de verdad.
 */
@Slf4j
@RestControllerAdvice
public class DegradacionCajeroAdvice {

    @ExceptionHandler(CuentasNoDisponible.class)
    public ResponseEntity<OperacionDto.ResultadoRetiro> fueraDeServicio(CuentasNoDisponible e) {

        boolean abierto = e.isCircuitoAbierto();
        boolean fueUnRetiro = e.getMessage() != null && e.getMessage().contains("retirar(");

        log.warn("Cajero fuera de servicio: {}", e.getMessage());

        String codigo = fueUnRetiro ? "VERIFIQUE_SALDO" : "FUERA_DE_SERVICIO";
        String mensaje;
        if (fueUnRetiro) {
            mensaje = "No pudimos confirmar el resultado de la operacion. "
                    + "Verifique su saldo antes de volver a intentar.";
        } else if (abierto) {
            mensaje = "Cajero fuera de servicio. Acerquese a una sucursal.";
        } else {
            mensaje = "Cajero momentaneamente fuera de servicio. Intente nuevamente.";
        }

        // Se responde con la MISMA forma que un retiro normal, no con un error
        // generico: el software del terminal sabe pintar un ResultadoRetiro y
        // no tendria por que aprender un segundo formato para el caso de falla.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", abierto ? "10" : "3")
                .body(new OperacionDto.ResultadoRetiro(false, codigo, mensaje, null, null));
    }
}

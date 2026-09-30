package cl.duoc.bank.bff.web.api;

import cl.duoc.bank.cliente.CuentasNoDisponible;
import cl.duoc.bank.cliente.TransferenciasNoDisponible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * La respuesta alternativa del canal web cuando ms-cuentas no contesta.
 *
 * POR QUE CADA CANAL TIENE LA SUYA
 * ================================
 * El fallback de bank-cliente no decide que se le responde al cliente final:
 * informa que el Backend no esta disponible y deja que cada BFF elija. Aqui
 * esta la eleccion del canal web, y es distinta de la del movil y de la del
 * cajero precisamente porque los tres atienden situaciones distintas.
 *
 * Un navegador puede recibir una explicacion y un tiempo sugerido de reintento
 * y mostrar un aviso decente sin perder el resto de la pagina. Por eso esta
 * respuesta lleva texto para una persona y el dato de si el circuito esta
 * abierto, que le permite a la interfaz distinguir "algo fallo, reintenta" de
 * "el sistema esta degradado, espera".
 *
 * Que esto viva en el BFF y no en el cliente compartido es la misma linea que
 * separa las reglas del negocio de las del canal en el resto del proyecto.
 *
 * EL CODIGO ES 503 Y NO 500
 * =========================
 * 500 dice "me rompi", y este servicio no se rompio: esta funcionando y su
 * dependencia no. 503 ademas admite la cabecera Retry-After, que es la forma
 * estandar de decirle a un cliente -y a un balanceador- cuanto conviene
 * esperar antes de volver a intentar.
 */
@Slf4j
@RestControllerAdvice
public class DegradacionWebAdvice {

    /** Lo que se le responde al navegador. */
    public record ServicioDegradado(
            boolean disponible,
            boolean circuitoAbierto,
            String mensaje,
            int reintentarEnSegundos) {
    }

    @ExceptionHandler(CuentasNoDisponible.class)
    public ResponseEntity<ServicioDegradado> noDisponible(CuentasNoDisponible e) {

        boolean abierto = e.isCircuitoAbierto();

        // Si el circuito esta abierto se sabe cuanto falta para que vuelva a
        // probar; si fue un fallo suelto, no hay dato y se sugiere poco.
        int reintento = abierto ? 10 : 3;

        log.warn("Respuesta degradada del canal web: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(reintento))
                .body(new ServicioDegradado(
                        false,
                        abierto,
                        abierto
                                ? "El servicio de cuentas esta temporalmente fuera de linea. "
                                  + "Estamos reintentando automaticamente."
                                : "No pudimos obtener los datos de la cuenta en este momento.",
                        reintento));
    }

    /**
     * ms-transferencias no responde. La transferencia NO se acepto: a
     * diferencia del retiro del cajero en la semana 6, aqui si se puede
     * afirmar, porque la solicitud viaja con clave de idempotencia y
     * reintentarla con la misma clave nunca crea una segunda.
     */
    @ExceptionHandler(TransferenciasNoDisponible.class)
    public ResponseEntity<ServicioDegradado> transferenciasNoDisponible(TransferenciasNoDisponible e) {
        int reintento = e.isCircuitoAbierto() ? 10 : 3;
        log.warn("Respuesta degradada del canal web: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(reintento))
                .body(new ServicioDegradado(false, e.isCircuitoAbierto(),
                        "Las transferencias no estan disponibles en este momento. "
                                + "No se realizo ningun cargo; puede reintentar con la misma clave.",
                        reintento));
    }
}

package cl.duoc.bank.bff.movil.api;

import cl.duoc.bank.cliente.CuentasNoDisponible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * La respuesta alternativa del canal movil.
 *
 * DOS CAMPOS, Y ESO TAMBIEN ES EL PATRON BFF
 * ==========================================
 * El canal web responde a la misma falla con un texto explicativo, un aviso
 * de si el circuito esta abierto y un tiempo sugerido de reintento. Aqui van
 * dos campos y nada mas.
 *
 * El motivo es el mismo por el que la ficha de cuenta pesa 953 bytes en web y
 * 340 en movil: un telefono con red intermitente es justo el cliente al que
 * peor le viene recibir un parrafo, y la aplicacion movil ya tiene sus propios
 * textos traducidos, asi que un mensaje redactado por el servidor lo
 * descartaria. Lo unico que necesita saber es si reintentar ya o esperar.
 *
 * La degradacion es una decision de canal, igual que el recorte de campos.
 */
@Slf4j
@RestControllerAdvice
public class DegradacionMovilAdvice {

    /** reintentar = false significa esperar; la app decide como mostrarlo. */
    public record NoDisponible(boolean disponible, boolean reintentar) {
    }

    @ExceptionHandler(CuentasNoDisponible.class)
    public ResponseEntity<NoDisponible> noDisponible(CuentasNoDisponible e) {

        log.warn("Respuesta degradada del canal movil: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", e.isCircuitoAbierto() ? "10" : "3")
                // Con el circuito abierto no tiene sentido que el telefono
                // reintente: la llamada ni siquiera saldria a la red.
                .body(new NoDisponible(false, !e.isCircuitoAbierto()));
    }
}

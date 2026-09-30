package cl.duoc.bank.bff.cajero.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Peticiones y respuestas del inicio de sesion en el terminal. */
public class SesionDto {

    /** Lo que envia el cajero cuando el cliente inserta la tarjeta y digita su PIN. */
    public record Solicitud(
            @NotNull(message = "La tarjeta es obligatoria")
            Long cuentaId,

            @NotBlank(message = "El PIN es obligatorio")
            @Pattern(regexp = "[0-9]{4}", message = "El PIN son 4 digitos")
            String pin) {
    }

    /**
     * Sesion de tarjeta: un identificador opaco, no un token.
     *
     * SEMANA 8: hasta la semana 7 esto era un JWT firmado por el propio BFF. Un
     * JWT se valida solo, sin consultar a nadie, y justamente por eso es dificil
     * de revocar: hubo que llevar una lista aparte para invalidarlo despues del
     * retiro. Una sesion opaca vive SOLO en este BFF: revocarla es borrarla, y
     * no significa nada fuera de aqui. Ademas queda atada al terminal que la
     * abrio (ver SesionCajeroService).
     *
     * Dura dos minutos. Una sesion de cajero es una persona parada frente a una
     * maquina en la calle: si se va sin cerrar, la ventana en que alguien podria
     * continuar su sesion tiene que ser lo mas corta posible.
     */
    public record Respuesta(String sesion, long expiraEnSegundos) {
    }
}

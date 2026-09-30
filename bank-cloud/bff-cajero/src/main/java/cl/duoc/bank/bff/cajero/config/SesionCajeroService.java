package cl.duoc.bank.bff.cajero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sesiones de tarjeta del cajero: quien inserto que tarjeta, en que terminal,
 * hasta cuando.
 *
 * SEMANA 8: la sesion ya no es un JWT sino un identificador opaco de 256 bits
 * que solo tiene sentido aqui. Tres propiedades que el JWT de la semana 7 no
 * tenia, o tenia a medias:
 *   - revocarla es borrarla: no hay una lista aparte de tokens invalidados;
 *   - esta ATADA AL TERMINAL: se guarda el cliente OAuth 2.0 que la abrio
 *     (cajero-terminal-01) y solo ese terminal puede usarla. Si el
 *     identificador se filtrara, no serviria desde otra maquina;
 *   - no contiene nada: la cuenta no viaja en ella, se busca de este lado.
 *
 * ALCANCE: las sesiones viven en la memoria de este proceso. Con un solo BFF de
 * cajero es suficiente; con varias instancias irian a un almacen compartido
 * (Redis) para que cualquier instancia reconozca la sesion.
 */
@Slf4j
@Service
public class SesionCajeroService {

    private static final SecureRandom AZAR = new SecureRandom();

    /** Una sesion abierta: de quien es la tarjeta, desde que terminal, hasta cuando. */
    public record Sesion(Long cuentaId, String terminal, Instant vence) {
    }

    private final Map<String, Sesion> vigentes = new ConcurrentHashMap<>();
    private final byte[] pinValido;
    private final Duration duracion;

    public SesionCajeroService(@Value("${bank.cajero.pin:1234}") String pinValido,
                               @Value("${bank.cajero.duracion-sesion:PT2M}") Duration duracion) {
        this.pinValido = pinValido.getBytes(StandardCharsets.UTF_8);
        this.duracion = duracion;
    }

    /** Comparacion en tiempo constante: no revela por el tiempo de respuesta cuantos digitos acerto. */
    public boolean pinCorrecto(String pin) {
        return pin != null && MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), pinValido);
    }

    public String abrir(Long cuentaId, String terminal) {
        limpiarVencidas();
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        vigentes.put(id, new Sesion(cuentaId, terminal, Instant.now().plus(duracion)));
        log.info("Sesion abierta en {} para la tarjeta {}", terminal, cuentaId);
        return id;
    }

    /**
     * La sesion, si existe, no vencio y la presenta el MISMO terminal que la
     * abrio. Cualquier otro caso es "no hay sesion".
     */
    public Optional<Sesion> vigente(String id, String terminal) {
        if (id == null) {
            return Optional.empty();
        }
        Sesion s = vigentes.get(id);
        if (s == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(s.vence())) {
            vigentes.remove(id);
            return Optional.empty();
        }
        if (!s.terminal().equals(terminal)) {
            log.warn("Sesion de {} presentada desde otro terminal ({}): rechazada", s.terminal(), terminal);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void cerrar(String id) {
        if (id != null && vigentes.remove(id) != null) {
            log.info("Sesion cerrada tras completar la operacion");
        }
    }

    public long duracionSegundos() {
        return duracion.toSeconds();
    }

    private void limpiarVencidas() {
        Instant ahora = Instant.now();
        vigentes.entrySet().removeIf(e -> ahora.isAfter(e.getValue().vence()));
    }
}

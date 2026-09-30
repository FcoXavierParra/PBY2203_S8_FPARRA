package cl.duoc.bank.transferencias.eventos;

import cl.duoc.bank.transferencias.dominio.EventoSaliente;
import cl.duoc.bank.transferencias.dominio.Repositorios;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Vacia el outbox hacia el broker, en orden.
 *
 * Cada fila se marca publicada DESPUES de enviarla. Si el proceso muere entre
 * las dos cosas, la fila se enviara otra vez al volver: at-least-once. El
 * broker descarta el segundo envio porque trae el mismo _AMQ_DUPL_ID, y si
 * aun asi llegara, el consumidor es idempotente.
 *
 * AL PRIMER FALLO, SE DETIENE LA VUELTA
 * =====================================
 * No se salta la fila que fallo para seguir con las siguientes. Si el broker
 * no acepta una, no va a aceptar la de atras, y saltarla desordenaria los
 * eventos: el cierre de una saga podria publicarse antes que su solicitud.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicadorOutbox {

    private static final int LOTE = 100;

    private final Repositorios.Salientes salientes;
    private final EnvioBroker envio;

    /** Para no repetir el mismo aviso en cada vuelta mientras el circuito sigue abierto. */
    private boolean avisadoCircuitoAbierto;

    @Scheduled(fixedDelayString = "${bank.outbox.intervalo-ms:500}")
    public void publicarPendientes() {
        List<EventoSaliente> pendientes = salientes.findByPublicadoEnIsNullOrderByCreadoEnAsc(Limit.of(LOTE));
        if (pendientes.isEmpty()) {
            return;
        }

        int enviados = 0;
        for (EventoSaliente fila : pendientes) {
            try {
                envio.enviar(fila);
            } catch (CallNotPermittedException e) {
                if (!avisadoCircuitoAbierto) {
                    log.warn("Circuito del broker ABIERTO: {} evento(s) esperan en el outbox. "
                            + "Las transferencias se siguen aceptando.", salientes.countByPublicadoEnIsNull());
                    avisadoCircuitoAbierto = true;
                }
                break;
            } catch (Exception e) {
                fila.setIntentos(fila.getIntentos() + 1);
                salientes.save(fila);
                log.warn("No se pudo publicar el evento {} (intento {}): {}",
                        fila.getEventoId(), fila.getIntentos(), e.toString());
                break;
            }
            fila.setPublicadoEn(Instant.now());
            fila.setIntentos(fila.getIntentos() + 1);
            salientes.save(fila);
            enviados++;
        }

        if (enviados > 0 && avisadoCircuitoAbierto) {
            log.info("Broker disponible otra vez: el outbox reanudo la publicacion");
            avisadoCircuitoAbierto = false;
        }
    }
}

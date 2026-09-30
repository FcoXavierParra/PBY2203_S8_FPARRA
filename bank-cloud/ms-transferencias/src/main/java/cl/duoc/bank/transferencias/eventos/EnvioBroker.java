package cl.duoc.bank.transferencias.eventos;

import cl.duoc.bank.contrato.eventos.EventoTransferencia;
import cl.duoc.bank.contrato.eventos.TransferenciaCerrada;
import cl.duoc.bank.contrato.eventos.TransferenciaSolicitada;
import cl.duoc.bank.eventos.PublicadorEventos;
import cl.duoc.bank.transferencias.dominio.EventoSaliente;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * El envio de UNA fila del outbox al broker, protegido por Circuit Breaker.
 *
 * QUE PROTEGE EL CIRCUITO AQUI
 * ============================
 * En la semana 6 el Circuit Breaker protegia a un usuario que espera: sin el,
 * un BFF se quedaba colgado esperando a un ms-cuentas caido. Aqui nadie
 * espera -la transferencia ya se acepto y el cliente ya tiene su 202-, asi que
 * lo que protege es otra cosa: al propio servicio y al broker.
 *
 * Sin circuito, con el broker caido, cada vuelta del publicador intentaria
 * abrir una conexion por cada fila pendiente, esperaria su timeout y
 * fallaria. Con cien filas son cien intentos cada medio segundo, llenando el
 * log y ocupando hilos. Y cuando el broker vuelve, lo recibe una avalancha de
 * reconexiones simultaneas justo mientras arranca.
 *
 * Con el circuito, despues de unos pocos fallos deja de intentar del todo
 * durante un rato; despues deja pasar UNA sonda y, si sale bien, vacia el
 * outbox. Las filas no se pierden en ningun momento: estan en la base.
 *
 * SIN FALLBACK, A PROPOSITO
 * =========================
 * En los BFF el fallback fabrica una respuesta degradada para el usuario. Aqui
 * no hay nada razonable que devolver en lugar de "publicado": la fila tiene
 * que quedar pendiente, y eso es exactamente lo que pasa si la excepcion llega
 * a PublicadorOutbox.
 */
@Component
@RequiredArgsConstructor
public class EnvioBroker {

    /** Nombre de la instancia de Resilience4j. Ver ms-transferencias.yml. */
    public static final String CB = "broker";

    private static final Map<String, Class<? extends EventoTransferencia>> TIPOS = Map.of(
            "TransferenciaSolicitada", TransferenciaSolicitada.class,
            "TransferenciaCerrada", TransferenciaCerrada.class);

    private final PublicadorEventos publicador;
    private final ObjectMapper json;

    @CircuitBreaker(name = CB)
    public void enviar(EventoSaliente fila) throws Exception {
        Class<? extends EventoTransferencia> tipo = TIPOS.get(fila.getTipo());
        if (tipo == null) {
            throw new IllegalStateException("Tipo de evento que este servicio no publica: " + fila.getTipo());
        }
        publicador.publicar(fila.getTopico(), json.readValue(fila.getPayload(), tipo));
    }
}

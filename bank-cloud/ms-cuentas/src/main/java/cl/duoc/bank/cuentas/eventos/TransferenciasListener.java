package cl.duoc.bank.cuentas.eventos;

import cl.duoc.bank.contrato.eventos.Topicos;
import cl.duoc.bank.contrato.eventos.TransferenciaAplicada;
import cl.duoc.bank.contrato.eventos.TransferenciaRechazada;
import cl.duoc.bank.contrato.eventos.TransferenciaSolicitada;
import cl.duoc.bank.core.servicio.TransferenciaService;
import cl.duoc.bank.eventos.EventosConfig;
import cl.duoc.bank.eventos.PublicadorEventos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ms-cuentas como participante de la saga.
 *
 * Reacciona a TransferenciaSolicitada y responde con TransferenciaAplicada o
 * TransferenciaRechazada. Nadie se lo ordena: es coreografia. ms-transferencias
 * no sabe que ms-cuentas existe, solo que alguien respondera en los topicos de
 * resultado.
 *
 * ESCALABILIDAD
 * =============
 * La suscripcion se llama "ms-cuentas", igual en todas las instancias. Levantar
 * una segunda instancia -mismo jar, otro puerto- basta para que el broker
 * reparta los eventos entre las dos. Ver EventosConfig.
 *
 * EL ORDEN DE LAS DOS ESCRITURAS
 * ==============================
 * Primero se confirma la base (TransferenciaService, @Transactional) y despues
 * se publica el resultado. Si el proceso muere entre las dos, el mensaje de
 * entrada no se confirmo al broker y se reentrega; la segunda vez
 * TransferenciaService lo reconoce como duplicado, no toca los saldos y
 * devuelve la respuesta guardada, que se publica con el MISMO eventoId. Si la
 * primera publicacion si habia salido, el broker descarta la segunda por
 * duplicada. En ningun caso el dinero se mueve dos veces ni la saga queda sin
 * respuesta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferenciasListener {

    private final TransferenciaService servicio;
    private final PublicadorEventos publicador;
    private final EstadisticasEventos estadisticas;

    @Value("${bank.instancia}")
    private String instancia;

    /**
     * Costo simulado de procesar una transferencia, en milisegundos.
     *
     * Representa lo que en un banco real ocurre antes de mover el dinero -una
     * consulta al motor antifraude, al core bancario- y que aqui no existe.
     * Sin el, procesar un evento toma un par de milisegundos y la diferencia
     * entre una y dos instancias no se puede medir. Es un valor de la
     * configuracion (ms-cuentas.yml) y la evidencia informa cual se uso.
     *
     * Se aplica ANTES de abrir la transaccion, para no retener los bloqueos de
     * las cuentas mientras se espera.
     */
    @Value("${bank.eventos.costo-procesamiento-ms:0}")
    private long costoProcesamientoMs;

    @JmsListener(
            destination = Topicos.TRANSFERENCIA_SOLICITADA,
            subscription = "ms-cuentas",
            containerFactory = EventosConfig.FABRICA_TOPICOS,
            concurrency = "${bank.eventos.concurrencia:1}")
    public void alSolicitarse(TransferenciaSolicitada e) throws InterruptedException {

        if (costoProcesamientoMs > 0) {
            Thread.sleep(costoProcesamientoMs);
        }

        TransferenciaService.Resultado r = servicio.aplicar(
                e.eventoId(), e.transferenciaId(), e.cuentaOrigen(), e.cuentaDestino(), e.monto(), instancia);

        estadisticas.registrar(r);

        if (r.aplicada()) {
            publicador.publicar(Topicos.TRANSFERENCIA_APLICADA, new TransferenciaAplicada(
                    r.respuestaId(), e.transferenciaId(), e.eventoId(),
                    e.cuentaOrigen(), e.cuentaDestino(), e.monto(),
                    r.saldoOrigen(), r.saldoDestino(), instancia, Instant.now()));
        } else {
            publicador.publicar(Topicos.TRANSFERENCIA_RECHAZADA, new TransferenciaRechazada(
                    r.respuestaId(), e.transferenciaId(), e.eventoId(),
                    r.motivo(), instancia, Instant.now()));
        }
    }
}

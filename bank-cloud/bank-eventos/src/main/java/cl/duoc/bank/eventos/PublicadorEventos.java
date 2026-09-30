package cl.duoc.bank.eventos;

import cl.duoc.bank.contrato.eventos.EventoTransferencia;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;

/**
 * Publica un evento con las dos marcas que el resto del sistema espera.
 *
 *   JMSCorrelationID = transferenciaId  agrupa todos los mensajes de una saga
 *   _AMQ_DUPL_ID     = eventoId         el broker descarta un segundo envio
 *                                       del mismo evento. Ver BrokerConfig.
 *
 * Que las ponga una sola clase es lo que garantiza que ningun servicio publique
 * sin ellas. Un evento sin _AMQ_DUPL_ID no rompe nada visible: simplemente deja
 * de estar protegido contra el reenvio del outbox, y eso no se nota hasta que
 * alguien recibe una transferencia dos veces.
 */
@Slf4j
@RequiredArgsConstructor
public class PublicadorEventos {

    /** Propiedad con la que Artemis detecta duplicados. */
    static final String DUPLICADO_ID = "_AMQ_DUPL_ID";

    private final JmsTemplate jms;

    public void publicar(String topico, EventoTransferencia evento) {
        jms.convertAndSend(topico, evento, m -> {
            m.setJMSCorrelationID(evento.transferenciaId());
            m.setStringProperty(DUPLICADO_ID, evento.eventoId());
            return m;
        });
        log.info("Publicado {} en {} (saga {}, evento {})",
                evento.getClass().getSimpleName(), topico, evento.transferenciaId(), evento.eventoId());
    }
}

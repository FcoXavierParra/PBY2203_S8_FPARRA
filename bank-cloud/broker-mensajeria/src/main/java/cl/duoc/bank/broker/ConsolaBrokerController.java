package cl.duoc.bank.broker;

import cl.duoc.bank.contrato.eventos.Topicos;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consola de administracion del broker, por HTTP.
 *
 * Existe por la evidencia. Artemis trae una consola web completa, pero es un
 * modulo aparte y pesado; lo que la evidencia necesita es poco y concreto:
 *
 *   GET  /admin/topologia    cada topico, sus suscripciones, cuantos consumidores
 *                            tiene cada una y cuantos mensajes paso por ella.
 *                            Es lo que demuestra que las dos instancias de
 *                            ms-cuentas comparten UNA suscripcion.
 *   GET  /admin/dlq          lo que quedo en la cola de mensajes muertos.
 *   POST /admin/publicar     publicar un mensaje a mano, como administrador.
 *                            Sirve para las pruebas de duplicados y de
 *                            mensaje envenenado.
 *   POST /admin/probar-envio intentar publicar CON LAS CREDENCIALES DE OTRO
 *                            servicio, para demostrar que el broker lo rechaza.
 *
 * Todo exige el usuario administrador; ver SeguridadConsolaConfig.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ConsolaBrokerController {

    private final EmbeddedActiveMQ broker;
    private final BrokerProperties props;

    // -------------------------------------------------------------- LECTURA

    @GetMapping("/topologia")
    public List<Map<String, Object>> topologia() throws Exception {
        ActiveMQServer servidor = broker.getActiveMQServer();
        List<Map<String, Object>> salida = new ArrayList<>();

        List<String> direcciones = new ArrayList<>(Topicos.TODOS);
        direcciones.add(BrokerConfig.DLQ);

        for (String direccion : direcciones) {
            List<SimpleString> colas = new ArrayList<>(
                    servidor.bindingQuery(SimpleString.of(direccion)).getQueueNames());
            Collections.sort(colas);
            for (SimpleString nombre : colas) {
                Queue q = servidor.locateQueue(nombre);
                if (q == null) {
                    continue;
                }
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("topico", direccion);
                fila.put("suscripcion", nombre.toString());
                fila.put("consumidores", q.getConsumerCount());
                fila.put("pendientes", q.getMessageCount());
                fila.put("recibidos", q.getMessagesAdded());
                fila.put("confirmados", q.getMessagesAcknowledged());
                salida.add(fila);
            }
        }
        return salida;
    }

    /**
     * Recorre la DLQ sin consumirla: un QueueBrowser mira los mensajes y los
     * deja donde estan.
     */
    @GetMapping("/dlq")
    public List<Map<String, Object>> dlq() throws JMSException {
        List<Map<String, Object>> salida = new ArrayList<>();
        try (Connection c = fabricaAdmin().createConnection()) {
            c.start();
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            QueueBrowser browser = s.createBrowser(s.createQueue(BrokerConfig.DLQ));
            var e = browser.getEnumeration();
            while (e.hasMoreElements()) {
                Message m = (Message) e.nextElement();
                Map<String, Object> fila = new LinkedHashMap<>();
                // Artemis anota de donde venia el mensaje antes de apartarlo.
                fila.put("topicoOriginal", m.getStringProperty("_AMQ_ORIG_ADDRESS"));
                fila.put("suscripcion", m.getStringProperty("_AMQ_ORIG_QUEUE"));
                fila.put("tipo", m.getStringProperty(Topicos.PROPIEDAD_TIPO));
                fila.put("entregas", m.getIntProperty("JMSXDeliveryCount"));
                fila.put("cuerpo", m instanceof TextMessage t ? t.getText() : "(no es texto)");
                salida.add(fila);
            }
        }
        return salida;
    }

    // ------------------------------------------------------------ ESCRITURA

    /**
     * Publica un mensaje como administrador.
     *
     * @param topico      a donde
     * @param tipo        valor de la propiedad _tipo; los consumidores la usan
     *                    para saber a que record convertir el cuerpo
     * @param duplicadoId si viene, se publica con _AMQ_DUPL_ID: el broker lo
     *                    descartara si ya vio ese id. Si no viene, el mensaje
     *                    pasa el broker y el duplicado lo tiene que detectar
     *                    el consumidor.
     */
    @PostMapping("/publicar")
    public Map<String, Object> publicar(@RequestParam String topico,
                                        @RequestParam String tipo,
                                        @RequestParam(required = false) String duplicadoId,
                                        @RequestBody String cuerpo) throws JMSException {
        exigirTopicoConocido(topico);
        try (Connection c = fabricaAdmin().createConnection()) {
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer p = s.createProducer(s.createTopic(topico));
            TextMessage m = s.createTextMessage(cuerpo);
            m.setStringProperty(Topicos.PROPIEDAD_TIPO, tipo);
            if (duplicadoId != null && !duplicadoId.isBlank()) {
                m.setStringProperty("_AMQ_DUPL_ID", duplicadoId);
            }
            p.send(m);
        }
        log.info("Publicado a mano en {} (tipo {}, duplicadoId {})", topico, tipo, duplicadoId);
        return Map.of("publicado", true, "topico", topico, "tipo", tipo,
                "conDeteccionDeDuplicados", duplicadoId != null);
    }

    /**
     * Intenta publicar en nombre de otro usuario del broker.
     *
     * OJO: la publicacion es REAL. Si el usuario tiene permiso, el mensaje se
     * entrega. Por eso la evidencia la usa solo para los casos que deben ser
     * rechazados, y el cuerpo es un tipo que ningun consumidor conoce: si por
     * error pasara, terminaria en la DLQ y no en una saga.
     */
    @PostMapping("/probar-envio")
    public ResponseEntity<Map<String, Object>> probarEnvio(@RequestParam String usuario,
                                                           @RequestParam String clave,
                                                           @RequestParam String topico) {
        exigirTopicoConocido(topico);
        ActiveMQConnectionFactory f = new ActiveMQConnectionFactory(props.aceptor(), usuario, clave);
        try (f; Connection c = f.createConnection()) {
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer p = s.createProducer(s.createTopic(topico));
            TextMessage m = s.createTextMessage("{\"sonda\":true}");
            m.setStringProperty(Topicos.PROPIEDAD_TIPO, "SondaDePermisos");
            p.send(m);
            return ResponseEntity.ok(Map.of("usuario", usuario, "topico", topico, "resultado", "PERMITIDO"));
        } catch (JMSSecurityException e) {
            return ResponseEntity.status(403).body(Map.of(
                    "usuario", usuario, "topico", topico, "resultado", "RECHAZADO_POR_EL_BROKER",
                    "detalle", String.valueOf(e.getMessage())));
        } catch (JMSException e) {
            return ResponseEntity.status(502).body(Map.of(
                    "usuario", usuario, "topico", topico, "resultado", "ERROR",
                    "detalle", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------- SOPORTE

    private ActiveMQConnectionFactory fabricaAdmin() {
        BrokerProperties.Usuario admin = props.usuarios().stream()
                .filter(u -> BrokerConfig.ROL_ADMIN.equals(u.rol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No hay usuario administrador configurado"));
        return new ActiveMQConnectionFactory(props.aceptor(), admin.usuario(), admin.clave());
    }

    private static void exigirTopicoConocido(String topico) {
        if (!Topicos.TODOS.contains(topico)) {
            throw new IllegalArgumentException("Topico desconocido: " + topico);
        }
    }
}

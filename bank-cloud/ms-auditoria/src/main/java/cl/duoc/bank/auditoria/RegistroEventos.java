package cl.duoc.bank.auditoria;

import cl.duoc.bank.contrato.eventos.EventoTransferencia;
import cl.duoc.bank.contrato.eventos.Topicos;
import cl.duoc.bank.contrato.eventos.TransferenciaAplicada;
import cl.duoc.bank.contrato.eventos.TransferenciaCerrada;
import cl.duoc.bank.contrato.eventos.TransferenciaRechazada;
import cl.duoc.bank.contrato.eventos.TransferenciaSolicitada;
import cl.duoc.bank.eventos.EventosConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro de auditoria de la saga, y la reconstruccion de cada transferencia
 * a partir de el.
 *
 * EVENT SOURCING, EN SU MEDIDA JUSTA
 * ==================================
 * La guia presenta Event Sourcing como un patron donde los eventos son la
 * fuente unica de verdad y el estado se calcula aplicandolos en orden. Este
 * proyecto NO lo adopta para los saldos, y conviene decir por que: el saldo de
 * cada cuenta lo calculo el batch de la Experiencia 1 y vive como una columna
 * en la tabla que ese batch dejo. Reemplazarlo por un log de eventos obligaria
 * a reconstruir ese historial, que no existe como eventos.
 *
 * Donde SI encaja es aqui. Este servicio no guarda en que estado esta una
 * transferencia: guarda lo que le paso. El estado -PENDIENTE, COMPLETADA,
 * compensada o no- lo CALCULA cada vez que se lo piden, aplicando los eventos
 * en orden (ver reconstruir). Es una proyeccion en el sentido de la guia. Si
 * manana hiciera falta otra vista -cuanto tardo cada saga, que instancia
 * proceso mas- se calcula sobre los mismos eventos sin tocar nada de lo que ya
 * esta guardado.
 *
 * Y sirve como comprobacion independiente: si la reconstruccion desde los
 * eventos coincide con el estado que informa ms-transferencias, la saga se
 * comporto como se diseno. La evidencia compara las dos.
 *
 * EL ORDEN DE LLEGADA NO ES EL ORDEN DE LOS HECHOS
 * ================================================
 * Dentro de UNA suscripcion el broker entrega en orden de publicacion. Pero
 * este servicio tiene cuatro, una por topico, cada una con su propio hilo, y
 * entre ellas no hay orden garantizado. Paso de verdad: con ms-auditoria caido
 * se acumularon eventos en las cuatro, y al volver registro una
 * TransferenciaAplicada ANTES que la TransferenciaSolicitada que la provoco.
 *
 * Por eso la secuencia se guarda -dice cuando llego cada cosa, que tambien es
 * un dato de auditoria- pero la historia y la reconstruccion ordenan por
 * ocurridoEn, el momento del hecho segun quien lo publico, y a igual instante
 * por el paso de la saga al que corresponde cada tipo.
 */
@Slf4j
@RestController
@RequestMapping("/interno/auditoria")
@RequiredArgsConstructor
public class RegistroEventos {

    /** El paso de la saga de cada tipo: desempata eventos del mismo instante. */
    private static final Map<String, Integer> PASO = Map.of(
            "TransferenciaSolicitada", 1,
            "TransferenciaAplicada", 2,
            "TransferenciaRechazada", 2,
            "TransferenciaCerrada", 3);

    static final Comparator<EventoRegistrado> ORDEN_DE_LOS_HECHOS = Comparator
            .comparing(EventoRegistrado::getOcurridoEn)
            .thenComparing(e -> PASO.getOrDefault(e.getTipo(), 9));

    private final EventoRegistrado.Repositorio registro;
    private final ObjectMapper json;

    // =========================================================== SUSCRIPCION

    @JmsListener(destination = Topicos.TRANSFERENCIA_SOLICITADA, subscription = "ms-auditoria.solicitada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void solicitada(TransferenciaSolicitada e) {
        registrar(Topicos.TRANSFERENCIA_SOLICITADA, e);
    }

    @JmsListener(destination = Topicos.TRANSFERENCIA_APLICADA, subscription = "ms-auditoria.aplicada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void aplicada(TransferenciaAplicada e) {
        registrar(Topicos.TRANSFERENCIA_APLICADA, e);
    }

    @JmsListener(destination = Topicos.TRANSFERENCIA_RECHAZADA, subscription = "ms-auditoria.rechazada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void rechazada(TransferenciaRechazada e) {
        registrar(Topicos.TRANSFERENCIA_RECHAZADA, e);
    }

    @JmsListener(destination = Topicos.TRANSFERENCIA_CERRADA, subscription = "ms-auditoria.cerrada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void cerrada(TransferenciaCerrada e) {
        registrar(Topicos.TRANSFERENCIA_CERRADA, e);
    }

    private void registrar(String topico, EventoTransferencia e) {
        if (registro.existsByEventoId(e.eventoId())) {
            log.info("Evento {} ya registrado: duplicado ignorado", e.eventoId());
            return;
        }
        EventoRegistrado r = new EventoRegistrado();
        r.setEventoId(e.eventoId());
        r.setTipo(e.getClass().getSimpleName());
        r.setTopico(topico);
        r.setTransferenciaId(e.transferenciaId());
        try {
            r.setPayload(json.writeValueAsString(e));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        r.setOcurridoEn(e.ocurridoEn() != null ? e.ocurridoEn() : Instant.now());
        r.setRecibidoEn(Instant.now());
        registro.save(r);
    }

    // ============================================================= CONSULTA

    /** La historia de una transferencia y su estado reconstruido. */
    @GetMapping("/transferencias/{id}")
    public ResponseEntity<Map<String, Object>> historia(@PathVariable String id) {
        List<EventoRegistrado> eventos = new ArrayList<>(registro.findByTransferenciaIdOrderBySecuenciaAsc(id));
        eventos.sort(ORDEN_DE_LOS_HECHOS);
        if (eventos.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> linea = new ArrayList<>();
        for (EventoRegistrado e : eventos) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("secuencia", e.getSecuencia());
            f.put("tipo", e.getTipo());
            f.put("ocurridoEn", e.getOcurridoEn());
            f.put("datos", leer(e.getPayload()));
            linea.add(f);
        }

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("transferenciaId", id);
        salida.put("eventos", linea);
        salida.put("estadoReconstruido", reconstruir(eventos));
        return ResponseEntity.ok(salida);
    }

    @GetMapping("/resumen")
    public Map<String, Object> resumen() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEventos", registro.count());
        for (String tipo : List.of("TransferenciaSolicitada", "TransferenciaAplicada",
                "TransferenciaRechazada", "TransferenciaCerrada")) {
            m.put(tipo, registro.countByTipo(tipo));
        }
        return m;
    }

    /**
     * Aplica los eventos en orden, como en el ejemplo bancario de la guia
     * (saldo 0, +100, -50, +200 = 250), pero sobre el estado de una saga.
     */
    static Map<String, Object> reconstruir(List<EventoRegistrado> eventos) {
        String estado = "DESCONOCIDO";
        String procesadoPor = null;
        String motivo = null;
        Object monto = null;
        boolean compensada = false;
        List<String> pasos = new ArrayList<>();

        ObjectMapper m = new ObjectMapper();
        for (EventoRegistrado e : eventos) {
            JsonNode d = leerNodo(m, e.getPayload());
            switch (e.getTipo()) {
                case "TransferenciaSolicitada" -> {
                    estado = "PENDIENTE";
                    monto = d.path("monto").decimalValue();
                    pasos.add("1. solicitada: cupo reservado");
                }
                case "TransferenciaAplicada" -> {
                    estado = "APLICADA_EN_CUENTAS";
                    procesadoPor = d.path("procesadoPor").asText(null);
                    pasos.add("2. aplicada por " + procesadoPor);
                }
                case "TransferenciaRechazada" -> {
                    estado = "RECHAZADA_EN_CUENTAS";
                    procesadoPor = d.path("procesadoPor").asText(null);
                    motivo = d.path("motivo").asText(null);
                    pasos.add("2. rechazada por " + procesadoPor + ": " + motivo);
                }
                case "TransferenciaCerrada" -> {
                    estado = d.path("estadoFinal").asText(estado);
                    BigDecimal liberado = d.path("cupoLiberado").decimalValue();
                    compensada = liberado != null && liberado.signum() > 0;
                    pasos.add(compensada
                            ? "3. cerrada " + estado + ": COMPENSACION, cupo liberado " + liberado.toPlainString()
                            : "3. cerrada " + estado);
                }
                default -> pasos.add("?. tipo desconocido: " + e.getTipo());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("estado", estado);
        r.put("monto", monto);
        r.put("procesadoPor", procesadoPor);
        r.put("motivo", motivo);
        r.put("compensada", compensada);
        r.put("pasos", pasos);
        return r;
    }

    private Object leer(String payload) {
        return leerNodo(json, payload);
    }

    private static JsonNode leerNodo(ObjectMapper m, String payload) {
        try {
            return m.readTree(payload);
        } catch (JsonProcessingException e) {
            return m.createObjectNode().put("ilegible", payload);
        }
    }
}

package cl.duoc.bank.transferencias.api;

import cl.duoc.bank.contrato.EstadoTransferencia;
import cl.duoc.bank.contrato.SolicitudTransferencia;
import cl.duoc.bank.transferencias.dominio.Repositorios;
import cl.duoc.bank.transferencias.eventos.EnvioBroker;
import cl.duoc.bank.transferencias.servicio.SagaTransferencias;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * La API de ms-transferencias. La consume bff-web, y la evidencia para la
 * rafaga de carga.
 *
 * 202 ACCEPTED, NO 201 CREATED
 * ============================
 * Cuando esta respuesta sale, la transferencia NO ocurrio todavia: se acepto,
 * se reservo el cupo y el evento esta en camino. Responder 201 o 200 con un
 * "listo" le diria al cliente algo que este servicio no sabe. 202 es el
 * codigo HTTP que significa exactamente eso -"recibido, se procesara"- y la
 * cabecera Location dice donde preguntar como termino.
 *
 * Es el costo visible de la arquitectura asincrona: el cliente ya no recibe
 * el resultado en la misma respuesta. A cambio, la solicitud se acepta aunque
 * ms-cuentas o el broker esten caidos en ese momento.
 */
@RestController
@RequestMapping("/interno/transferencias")
@RequiredArgsConstructor
public class TransferenciaInternaController {

    private final SagaTransferencias saga;
    private final Repositorios.Salientes salientes;
    private final Repositorios.Transferencias transferencias;
    private final CircuitBreakerRegistry circuitos;

    @PostMapping
    public ResponseEntity<EstadoTransferencia> solicitar(
            @RequestBody SolicitudTransferencia solicitud,
            @RequestHeader(name = "Idempotency-Key", required = false) String clave) {

        EstadoTransferencia e = saga.solicitar(solicitud, clave);
        return ResponseEntity.accepted()
                .location(URI.create("/interno/transferencias/" + e.transferenciaId()))
                .body(e);
    }

    /**
     * Varias solicitudes en una sola llamada. Existe para la prueba de carga.
     *
     * Con una llamada HTTP por transferencia, el que no da abasto es el
     * script que las genera -cada curl.exe es un proceso nuevo- y no el
     * consumidor, y la medicion de escalabilidad terminaria midiendo la
     * velocidad de PowerShell. Asi las N solicitudes quedan en el outbox en
     * una fraccion de segundo y lo que se mide es cuanto tarda ms-cuentas en
     * procesarlas.
     *
     * Cada una pasa por el mismo solicitar() y en su propia transaccion: una
     * que excede el cupo no arrastra a las demas.
     */
    @PostMapping("/lote")
    public Map<String, Object> lote(@RequestBody java.util.List<SolicitudTransferencia> solicitudes) {
        int aceptadas = 0;
        int rechazadas = 0;
        for (SolicitudTransferencia s : solicitudes) {
            try {
                saga.solicitar(s, null);
                aceptadas++;
            } catch (SagaTransferencias.SolicitudRechazada e) {
                rechazadas++;
            }
        }
        return Map.of("aceptadas", aceptadas, "rechazadasEnElActo", rechazadas);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EstadoTransferencia> estado(@PathVariable String id) {
        return saga.estado(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/cupo/{cuenta}")
    public Map<String, Object> cupo(@PathVariable Long cuenta) {
        BigDecimal reservado = saga.reservadoHoy(cuenta);
        return Map.of(
                "cuenta", cuenta,
                "cupoDiario", saga.cupoDiario(),
                "reservado", reservado,
                "disponible", saga.cupoDiario().subtract(reservado));
    }

    /**
     * Estado del outbox y del circuito que lo protege. Es lo que la evidencia
     * consulta mientras el broker esta caido.
     */
    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendientesDePublicar", salientes.countByPublicadoEnIsNull());
        m.put("publicados", salientes.countByPublicadoEnIsNotNull());
        m.put("circuitoBroker", circuitos.circuitBreaker(EnvioBroker.CB).getState().name());
        m.put("transferenciasPendientes", transferencias.countByEstado("PENDIENTE"));
        m.put("transferenciasCompletadas", transferencias.countByEstado("COMPLETADA"));
        m.put("transferenciasRechazadas", transferencias.countByEstado("RECHAZADA"));
        return m;
    }

    /** Las reglas de este servicio rechazan en el acto, con 422 y sin saga. */
    @ExceptionHandler(SagaTransferencias.SolicitudRechazada.class)
    public ResponseEntity<Map<String, String>> rechazada(SagaTransferencias.SolicitudRechazada e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("codigo", e.getCodigo(), "mensaje", e.getMessage()));
    }
}

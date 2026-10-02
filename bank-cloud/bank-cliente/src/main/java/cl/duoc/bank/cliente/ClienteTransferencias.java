package cl.duoc.bank.cliente;

import cl.duoc.bank.contrato.EstadoTransferencia;
import cl.duoc.bank.contrato.SolicitudTransferencia;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

/**
 * Cliente HTTP de ms-transferencias. Lo usa solo bff-web.
 *
 * AHORA SI SE REINTENTA LA OPERACION QUE MUEVE DINERO
 * ===================================================
 * En la semana 6 el retiro del cajero NO llevaba @Retry, y el javadoc de
 * ClienteCuentas explicaba por que: sin clave de idempotencia, reintentar una
 * operacion que ya pudo aplicarse al otro lado es la forma clasica de cobrar
 * dos veces. La solucion quedo anotada como fuera de alcance.
 *
 * Esta semana esta implementada. Cada solicitud viaja con una Idempotency-Key
 * que genera el BFF, y ms-transferencias la guarda con restriccion de
 * unicidad: la misma clave dos veces devuelve la misma transferencia en vez de
 * crear otra. Con eso el reintento pasa a ser seguro -si la respuesta se
 * perdio, el segundo intento recibe la transferencia que el primero ya creo- y
 * por eso solicitar() lleva @Retry, igual que las consultas.
 *
 * Un 422 -cupo excedido, misma cuenta- es una respuesta de negocio y no cuenta
 * como fallo del circuito, por el mismo motivo que un 404 en ClienteCuentas.
 *
 * SEMANA 8: BULKHEAD, UN COMPARTIMENTO POR DEPENDENCIA
 * ====================================================
 * bff-web llama a dos servicios, y todas sus peticiones comparten el mismo
 * grupo de hilos de Tomcat. Si ms-transferencias se pusiera lento -la base
 * esta en otro continente, el broker puede estar reconectando-, cada
 * transferencia en curso ocuparia un hilo hasta su timeout de 2,5 s, y con
 * trafico suficiente no quedarian hilos para NADA: tampoco para consultar
 * cuentas, que no tienen ningun problema. El Circuit Breaker no lo evita, porque
 * lento no es lo mismo que caido y el circuito tarda varias llamadas en abrir.
 *
 * El Bulkhead pone un tope: a lo mas 5 llamadas simultaneas hacia
 * ms-transferencias (ms-cuentas tiene su propio compartimento de 20). La sexta
 * no espera: se rechaza al instante con BulkheadFullException, el canal
 * responde 503 y el hilo queda libre para otra cosa. Es el mismo principio que
 * los mamparos de un barco: una via de agua inunda un compartimento, no el
 * casco entero. Numeros en config-repo/application.yml y bff-web.yml.
 */
@Slf4j
@RequiredArgsConstructor
public class ClienteTransferencias {

    private static final String CB = "msTransferencias";

    private final RestClient rest;

    /**
     * La respuesta a una solicitud: aceptada (202) o rechazada en el acto por
     * las reglas de ms-transferencias (422).
     */
    public record Respuesta(EstadoTransferencia aceptada, String codigoRechazo, String mensajeRechazo) {

        public boolean fueAceptada() {
            return aceptada != null;
        }
    }

    @CircuitBreaker(name = CB, fallbackMethod = "solicitudNoDisponible")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public Respuesta solicitar(SolicitudTransferencia s, String claveIdempotencia) {
        return rest.post()
                .uri("/interno/transferencias")
                .header("Idempotency-Key", claveIdempotencia)
                .body(s)
                .exchange((req, res) -> {
                    int status = res.getStatusCode().value();
                    if (status == 422) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = res.bodyTo(Map.class);
                        return new Respuesta(null,
                                c == null ? null : String.valueOf(c.get("codigo")),
                                c == null ? null : String.valueOf(c.get("mensaje")));
                    }
                    if (res.getStatusCode().is2xxSuccessful()) {
                        return new Respuesta(res.bodyTo(EstadoTransferencia.class), null, null);
                    }
                    // Cualquier otra cosa -un 401 por credencial mal configurada,
                    // un 500- es una falla y el circuito tiene que contarla.
                    throw new RestClientResponseException("ms-transferencias respondio " + status,
                            status, res.getStatusText(), res.getHeaders(), null, null);
                });
    }

    @CircuitBreaker(name = CB, fallbackMethod = "estadoNoDisponible")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public Optional<EstadoTransferencia> estado(String transferenciaId) {
        ResponseEntity<EstadoTransferencia> r = rest.get()
                .uri("/interno/transferencias/{id}", transferenciaId)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .toEntity(EstadoTransferencia.class);
        return r.getStatusCode().value() == 404 ? Optional.empty() : Optional.ofNullable(r.getBody());
    }

    // -------------------------------------------------------------- FALLBACK

    private Respuesta solicitudNoDisponible(SolicitudTransferencia s, String clave, Throwable causa) {
        throw traducir("solicitar", causa);
    }

    private Optional<EstadoTransferencia> estadoNoDisponible(String id, Throwable causa) {
        throw traducir("estado(" + id + ")", causa);
    }

    private TransferenciasNoDisponible traducir(String operacion, Throwable causa) {
        boolean abierto = causa instanceof CallNotPermittedException;
        if (abierto) {
            log.warn("Circuito ABIERTO hacia ms-transferencias: '{}' no se intento", operacion);
        } else if (causa instanceof BulkheadFullException) {
            log.warn("Bulkhead LLENO hacia ms-transferencias: '{}' rechazada sin salir a la red", operacion);
        } else {
            log.warn("Fallo la llamada '{}' a ms-transferencias: {}", operacion, causa.toString());
        }
        return new TransferenciasNoDisponible(operacion, causa, abierto);
    }
}

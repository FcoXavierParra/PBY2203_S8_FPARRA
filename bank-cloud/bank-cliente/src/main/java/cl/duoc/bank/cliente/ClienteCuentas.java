package cl.duoc.bank.cliente;

import cl.duoc.bank.contrato.FichaCuenta;
import cl.duoc.bank.contrato.MovimientoCuenta;
import cl.duoc.bank.contrato.PaginaTransacciones;
import cl.duoc.bank.contrato.ResultadoRetiro;
import cl.duoc.bank.contrato.SolicitudRetiro;
import cl.duoc.bank.contrato.TransaccionBanco;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Cliente HTTP de ms-cuentas.
 *
 * ESTE ARCHIVO ES EL CAMBIO CENTRAL DE LA ENTREGA
 * -----------------------------------------------
 * En la Experiencia 2 los BFF llamaban a ConsultaService y OperacionService
 * directamente: una llamada a metodo, en el mismo proceso, que no puede
 * perderse, ni llegar tarde, ni encontrar al otro lado caido. La
 * retroalimentacion pidio convertir eso en integracion por HTTP con un
 * servicio Backend, y esa es la conversion.
 *
 * Lo que se gana es despliegue independiente: ms-cuentas se puede actualizar,
 * escalar a dos instancias o mover de maquina sin recompilar ningun BFF.
 *
 * Lo que se paga es que ahora la llamada puede fallar de maneras que antes no
 * existian, y por eso cada metodo de aqui lleva su proteccion. Esa es
 * exactamente la resiliencia que la semana pide: no aparece porque se agrego
 * una libreria, aparece porque el diseno creo el problema que la necesita.
 *
 * COMO SE DESCUBRE EL SERVICIO
 * ----------------------------
 * La URI es lb://ms-cuentas: un nombre logico, no una direccion. El
 * balanceador de Spring Cloud lo resuelve contra las instancias que Eureka
 * publica y reparte entre ellas. En ningun archivo de este proyecto esta
 * escrito "localhost:8090" del lado del cliente.
 *
 * QUE SE REINTENTA Y QUE NO
 * -------------------------
 * Las consultas llevan @Retry: leer dos veces no tiene efecto. El retiro NO lo
 * lleva, y es deliberado. Un reintento sobre una operacion que ya pudo
 * aplicarse al otro lado es la forma clasica de entregar el dinero dos veces
 * -se completa en ms-cuentas, la respuesta se pierde en la red, el cliente
 * reintenta y descuenta de nuevo-. Mientras el contrato no tenga clave de
 * idempotencia, reintentar es peor que fallar.
 */
@Slf4j
@RequiredArgsConstructor
public class ClienteCuentas {

    /** Nombre de la instancia de Resilience4j, el mismo en los tres canales. */
    private static final String CB = "msCuentas";

    private final RestClient rest;

    // --------------------------------------------------------------- LECTURA

    /**
     * Ficha de una cuenta, con sus agregados ya resueltos por el Backend.
     *
     * Un 404 devuelve Optional vacio y NO cuenta como fallo del circuito. La
     * distincion importa: "esa cuenta no existe" es el servicio funcionando
     * correctamente, y si contara como error, unas cuantas consultas a cuentas
     * inexistentes abririan el circuito y dejarian el canal degradado sin que
     * nada este roto.
     */
    @CircuitBreaker(name = CB, fallbackMethod = "fichaNoDisponible")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public Optional<FichaCuenta> ficha(Long cuentaId) {
        ResponseEntity<FichaCuenta> r = rest.get()
                .uri("/interno/cuentas/{id}", cuentaId)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .toEntity(FichaCuenta.class);

        return r.getStatusCode().value() == 404 ? Optional.empty() : Optional.ofNullable(r.getBody());
    }

    @CircuitBreaker(name = CB, fallbackMethod = "listaNoDisponible")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public List<FichaCuenta> listarCuentas() {
        FichaCuenta[] fichas = rest.get()
                .uri("/interno/cuentas")
                .retrieve()
                .body(FichaCuenta[].class);
        return fichas == null ? List.of() : List.of(fichas);
    }

    @CircuitBreaker(name = CB, fallbackMethod = "movimientosNoDisponibles")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public Optional<List<MovimientoCuenta>> movimientos(Long cuentaId) {
        ResponseEntity<MovimientoCuenta[]> r = rest.get()
                .uri("/interno/cuentas/{id}/movimientos", cuentaId)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .toEntity(MovimientoCuenta[].class);

        if (r.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        MovimientoCuenta[] cuerpo = r.getBody();
        return Optional.of(cuerpo == null ? List.of() : List.of(cuerpo));
    }

    @CircuitBreaker(name = CB, fallbackMethod = "paginaNoDisponible")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public PaginaTransacciones transacciones(LocalDate desde, LocalDate hasta, int pagina, int tamano) {
        return rest.get()
                .uri(uri -> uri.path("/interno/transacciones")
                        .queryParam("desde", desde)
                        .queryParam("hasta", hasta)
                        .queryParam("pagina", pagina)
                        .queryParam("tamano", tamano)
                        .build())
                .retrieve()
                .body(PaginaTransacciones.class);
    }

    @CircuitBreaker(name = CB, fallbackMethod = "ultimasNoDisponibles")
    @Bulkhead(name = CB)
    @Retry(name = CB)
    public List<TransaccionBanco> ultimasTransacciones() {
        TransaccionBanco[] t = rest.get()
                .uri("/interno/transacciones/ultimas")
                .retrieve()
                .body(TransaccionBanco[].class);
        return t == null ? List.of() : List.of(t);
    }

    // ------------------------------------------------------------- OPERACION

    /**
     * Retiro. Sin @Retry, a proposito: ver el javadoc de la clase.
     *
     * Un retiro rechazado por saldo insuficiente llega como 200 con autorizado
     * en false, asi que tampoco cuenta como fallo del circuito. Ver el javadoc
     * de ResultadoRetiro.
     */
    @CircuitBreaker(name = CB, fallbackMethod = "retiroNoDisponible")
    @Bulkhead(name = CB)
    public ResultadoRetiro retirar(Long cuentaId, BigDecimal monto) {
        return rest.post()
                .uri("/interno/cuentas/{id}/retiro", cuentaId)
                .body(new SolicitudRetiro(monto))
                .retrieve()
                .body(ResultadoRetiro.class);
    }

    // -------------------------------------------------------------- FALLBACK
    //
    // Resilience4j exige que cada metodo de respaldo repita la firma del
    // original y agregue el Throwable al final. Todos hacen lo mismo: registrar
    // y traducir el fallo a una excepcion del dominio del cliente, para que
    // ningun BFF tenga que saber si lo que fallo fue un socket, un timeout o un
    // circuito abierto. Ver el javadoc de CuentasNoDisponible para por que no
    // devuelven un valor por defecto.

    private Optional<FichaCuenta> fichaNoDisponible(Long cuentaId, Throwable causa) {
        throw traducir("ficha(" + cuentaId + ")", causa);
    }

    private List<FichaCuenta> listaNoDisponible(Throwable causa) {
        throw traducir("listarCuentas", causa);
    }

    private Optional<List<MovimientoCuenta>> movimientosNoDisponibles(Long cuentaId, Throwable causa) {
        throw traducir("movimientos(" + cuentaId + ")", causa);
    }

    private PaginaTransacciones paginaNoDisponible(LocalDate desde, LocalDate hasta,
                                                   int pagina, int tamano, Throwable causa) {
        throw traducir("transacciones", causa);
    }

    private List<TransaccionBanco> ultimasNoDisponibles(Throwable causa) {
        throw traducir("ultimasTransacciones", causa);
    }

    private ResultadoRetiro retiroNoDisponible(Long cuentaId, BigDecimal monto, Throwable causa) {
        throw traducir("retirar(" + cuentaId + ")", causa);
    }

    private CuentasNoDisponible traducir(String operacion, Throwable causa) {
        boolean abierto = causa instanceof CallNotPermittedException;
        if (abierto) {
            // Esta linea es la que prueba que el circuito hizo su trabajo: la
            // peticion ni siquiera salio a la red.
            log.warn("Circuito ABIERTO: '{}' no se intento contra ms-cuentas", operacion);
        } else if (causa instanceof BulkheadFullException) {
            // SEMANA 8: el compartimento de ms-cuentas esta lleno. No es una
            // falla de ms-cuentas -por eso el circuito la ignora, ver
            // application.yml- sino de este BFF protegiendose: ya hay demasiadas
            // llamadas en curso hacia ese servicio.
            log.warn("Bulkhead LLENO: '{}' rechazada sin salir a la red", operacion);
        } else {
            log.warn("Fallo la llamada '{}' a ms-cuentas: {}", operacion, causa.toString());
        }
        return new CuentasNoDisponible(operacion, causa, abierto);
    }
}

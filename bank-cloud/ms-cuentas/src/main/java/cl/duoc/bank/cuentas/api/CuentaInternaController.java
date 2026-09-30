package cl.duoc.bank.cuentas.api;

import cl.duoc.bank.contrato.FichaCuenta;
import cl.duoc.bank.contrato.MovimientoCuenta;
import cl.duoc.bank.contrato.PaginaTransacciones;
import cl.duoc.bank.contrato.ResultadoRetiro;
import cl.duoc.bank.contrato.SolicitudRetiro;
import cl.duoc.bank.contrato.TransaccionBanco;
import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.MovimientoAnual;
import cl.duoc.bank.core.dominio.Transaccion;
import cl.duoc.bank.core.servicio.ConsultaService;
import cl.duoc.bank.core.servicio.OperacionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * La API del servicio de dominio. Es lo que consumen los tres BFF por HTTP.
 *
 * POR QUE EL PREFIJO /interno
 * ---------------------------
 * Marca en la propia URL que este no es un endpoint de cara al publico. Ningun
 * navegador, telefono ni cajero llama aqui: llaman a su BFF, y el BFF llama
 * aqui. La distincion importa porque las reglas de autorizacion son distintas
 * -este servicio autentica SERVICIOS, no personas- y porque en un despliegue
 * real este puerto no estaria publicado fuera de la red interna.
 *
 * QUE NO HACE ESTE SERVICIO
 * -------------------------
 * No formatea montos, no decide segmentos etarios, no recorta campos y no sabe
 * que existen canales. Devuelve el dato del banco completo y en crudo; que el
 * movil muestre cuatro campos y la web trece es decision del BFF. Si este
 * servicio empezara a tener endpoints para un canal en particular, el patron
 * BFF se volveria decorativo y volveriamos al problema que la Experiencia 2
 * resolvio.
 *
 * Lo que si hace es AGREGAR -totales, promedios, conteos-, y esa es la
 * diferencia con la entrega anterior: los agregados que el BFF web calculaba
 * por su cuenta sobre los repositorios ahora los calcula el Backend y viajan
 * resueltos. Ver el javadoc de FichaCuenta.
 */
@Slf4j
@RestController
@RequestMapping("/interno")
@RequiredArgsConstructor
public class CuentaInternaController {

    private final ConsultaService consulta;
    private final OperacionService operaciones;

    /**
     * Retardo artificial, en milisegundos, antes de responder.
     *
     * Existe para poder demostrar el Circuit Breaker de los BFF sin tener que
     * matar este proceso: subiendo este valor por encima del timeout del
     * cliente, las llamadas empiezan a fallar, el circuito se abre y los tres
     * canales pasan a servir su respuesta alternativa. Vale 0 salvo que la
     * evidencia lo suba, y el valor llega desde el Config Server, que es
     * tambien la forma de mostrar que la configuracion centralizada funciona.
     */
    @Value("${bank.cuentas.latencia-simulada-ms:0}")
    private long latenciaSimulada;

    // ------------------------------------------------------------- CUENTAS

    /**
     * Listado completo, con los agregados de cada cuenta.
     *
     * POR QUE NO LLAMA A aFicha EN UN BUCLE
     * -------------------------------------
     * Porque aFicha consulta los movimientos de UNA cuenta, y con cincuenta
     * cuentas eso son cincuenta consultas a la base. La primera version de este
     * endpoint lo hacia asi y tardaba mas de dos segundos y medio, lo suficiente
     * para que el cliente cortara por timeout y el Circuit Breaker del canal web
     * contara el fallo: la evidencia mostraba un 503 al pedir la cartera
     * completa, que parecia una caida del servicio y era una consulta mal
     * planteada.
     *
     * Aqui se traen TODOS los movimientos de una vez y se agrupan en memoria.
     * Una consulta en vez de cincuenta y una.
     */
    @GetMapping("/cuentas")
    public List<FichaCuenta> listarCuentas() {
        demorar();

        Map<Long, List<MovimientoAnual>> porCuenta = consulta.todosLosMovimientos().stream()
                .filter(m -> m.getCuentaId() != null)
                .collect(Collectors.groupingBy(MovimientoAnual::getCuentaId));

        return consulta.listarCuentas().stream()
                .map(c -> aFicha(c, porCuenta.getOrDefault(c.getCuentaId(), List.of())))
                .toList();
    }

    @GetMapping("/cuentas/{cuentaId}")
    public ResponseEntity<FichaCuenta> cuenta(@PathVariable Long cuentaId) {
        demorar();
        return consulta.buscarCuenta(cuentaId)
                .map(c -> ResponseEntity.ok(aFicha(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Movimientos de UNA cuenta, desde MOVIMIENTO_ANUAL.
     *
     * Esta es la consulta que el canal movil debio usar desde el principio.
     * Ver el javadoc de MovimientoCuenta.
     */
    @GetMapping("/cuentas/{cuentaId}/movimientos")
    public ResponseEntity<List<MovimientoCuenta>> movimientos(@PathVariable Long cuentaId) {
        demorar();
        if (consulta.buscarCuenta(cuentaId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(consulta.movimientosDe(cuentaId).stream()
                .map(CuentaInternaController::aMovimiento)
                .toList());
    }

    // -------------------------------------------------------- TRANSACCIONES

    @GetMapping("/transacciones")
    public PaginaTransacciones transacciones(
            @RequestParam(defaultValue = "2024-01-01") LocalDate desde,
            @RequestParam(defaultValue = "2024-12-31") LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamano) {

        demorar();
        Page<Transaccion> resultado = consulta.transaccionesPaginadas(desde, hasta, pagina, tamano);
        return new PaginaTransacciones(
                resultado.getContent().stream().map(CuentaInternaController::aTransaccion).toList(),
                resultado.getNumber(),
                resultado.getSize(),
                resultado.getTotalElements(),
                resultado.getTotalPages(),
                resultado.hasNext());
    }

    @GetMapping("/transacciones/ultimas")
    public List<TransaccionBanco> ultimasTransacciones() {
        demorar();
        return consulta.ultimasTransacciones().stream()
                .map(CuentaInternaController::aTransaccion)
                .toList();
    }

    // ------------------------------------------------------------ OPERACION

    /**
     * Retiro. La regla que se aplica aqui es la del BANCO -que el saldo
     * alcance, de forma atomica-, no la del canal.
     *
     * El limite por giro y el multiplo de billete siguen viviendo en
     * bff-cajero, porque son politica del punto de atencion: un cajero entrega
     * billetes y la web no tiene esa restriccion. Mover esa regla aqui se la
     * impondria a todos los canales; mover la del saldo al BFF obligaria a
     * repetirla en cada uno y bastaria olvidarla una vez para permitir un
     * descubierto.
     */
    @PostMapping("/cuentas/{cuentaId}/retiro")
    public ResponseEntity<ResultadoRetiro> retirar(@PathVariable Long cuentaId,
                                                   @RequestBody SolicitudRetiro solicitud) {
        demorar();
        if (solicitud == null || solicitud.monto() == null
                || solicitud.monto().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().build();
        }

        OperacionService.Retiro r = operaciones.retirar(cuentaId, solicitud.monto());
        log.info("Retiro de {} sobre la cuenta {}: {}",
                solicitud.monto(), cuentaId, r.autorizado() ? "autorizado" : r.motivo());

        return ResponseEntity.ok(new ResultadoRetiro(r.autorizado(), r.motivo(), r.saldoResultante()));
    }

    // ------------------------------------------------------------- EVIDENCIA

    /** Conteos del dataset cargado. La evidencia lo usa para identificar el motor. */
    @GetMapping("/resumen")
    public Map<String, Long> resumen() {
        return Map.of(
                "cuentas", consulta.totalCuentas(),
                "transacciones", consulta.totalTransacciones());
    }

    // ----------------------------------------------------------------- MAPEO

    /** Ficha de una cuenta, buscando sus movimientos. Para consultas puntuales. */
    private FichaCuenta aFicha(Cuenta c) {
        return aFicha(c, consulta.movimientosDe(c.getCuentaId()));
    }

    /**
     * Cuenta mas sus agregados, sobre movimientos ya obtenidos.
     *
     * Recibe la lista en vez de buscarla para que el listado completo pueda
     * traerlas todas de una sola consulta. Ver listarCuentas.
     *
     * Una sola pasada por los movimientos: al recorrerlos tres veces
     * -depositos, retiros, promedio- se multiplicaba por tres el trabajo sobre
     * los mismos datos.
     */
    private FichaCuenta aFicha(Cuenta c, List<MovimientoAnual> movimientos) {

        BigDecimal depositos = BigDecimal.ZERO;
        BigDecimal retiros = BigDecimal.ZERO;
        BigDecimal suma = BigDecimal.ZERO;

        for (MovimientoAnual m : movimientos) {
            BigDecimal monto = Optional.ofNullable(m.getMonto()).orElse(BigDecimal.ZERO);
            suma = suma.add(monto);
            if ("deposito".equalsIgnoreCase(m.getTipoTransaccion())) {
                depositos = depositos.add(monto);
            } else if ("retiro".equalsIgnoreCase(m.getTipoTransaccion())) {
                retiros = retiros.add(monto);
            }
        }

        BigDecimal promedio = movimientos.isEmpty()
                ? BigDecimal.ZERO
                : suma.divide(BigDecimal.valueOf(movimientos.size()), 2, RoundingMode.HALF_UP);

        return new FichaCuenta(
                c.getCuentaId(),
                c.getNombre(),
                c.getTipo(),
                c.getEdad(),
                c.getSaldoInicial(),
                c.getTasaAplicada(),
                c.getInteresCalculado(),
                c.getSaldoFinal(),
                c.getObservacion(),
                movimientos.size(),
                depositos,
                retiros,
                promedio);
    }

    private static MovimientoCuenta aMovimiento(MovimientoAnual m) {
        return new MovimientoCuenta(
                m.getCuentaId(), m.getFecha(), m.getTipoTransaccion(), m.getMonto(), m.getDescripcion());
    }

    private static TransaccionBanco aTransaccion(Transaccion t) {
        return new TransaccionBanco(
                t.getId(), t.getFecha(), t.getMonto(), t.getTipo(), t.isAnomalia(), t.getMotivoAnomalia());
    }

    /** Ver el campo latenciaSimulada. */
    private void demorar() {
        if (latenciaSimulada <= 0) {
            return;
        }
        try {
            Thread.sleep(latenciaSimulada);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

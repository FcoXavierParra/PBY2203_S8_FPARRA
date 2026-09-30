package cl.duoc.bank.bff.web.api;

import cl.duoc.bank.bff.web.dto.CuentaWebDto;
import cl.duoc.bank.bff.web.dto.EstadoAnualWebDto;
import cl.duoc.bank.bff.web.dto.MovimientoAnualWebDto;
import cl.duoc.bank.bff.web.dto.PaginaWebDto;
import cl.duoc.bank.bff.web.dto.TransaccionWebDto;
import cl.duoc.bank.cliente.ClienteCuentas;
import cl.duoc.bank.contrato.FichaCuenta;
import cl.duoc.bank.contrato.MovimientoCuenta;
import cl.duoc.bank.contrato.PaginaTransacciones;
import cl.duoc.bank.contrato.TransaccionBanco;
import cl.duoc.bank.seguridad.AutorizacionCuenta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * La API del canal web.
 *
 * DOS CAMBIOS RESPECTO DE LA ENTREGA ANTERIOR
 * ===========================================
 *
 * 1. DE DONDE SALEN LOS DATOS. Antes este controlador tenia un ConsultaService
 *    inyectado y hablaba con los repositorios JPA en proceso. Ahora tiene un
 *    ClienteCuentas y habla por HTTP con ms-cuentas, descubierto por Eureka y
 *    protegido por Circuit Breaker. El dominio ni siquiera esta en el
 *    classpath de este modulo.
 *
 * 2. QUIEN PUEDE VER QUE. Antes los endpoints con {cuentaId} tomaban ese
 *    numero del path y consultaban sin mas: el usuario 'cliente' podia leer la
 *    ficha de cualquier cuenta del banco cambiando la URL. Ahora cada uno
 *    pregunta a AutorizacionCuenta, que compara contra la cuenta firmada
 *    dentro del token.
 *
 * EL ROL EJECUTIVO SIGUE EXISTIENDO Y SIGUE PUDIENDO VER TODO
 * ===========================================================
 * No es una excepcion que debilite la regla: es la regla. Un ejecutivo de
 * cuentas tiene atribucion sobre la cartera y el sistema tiene que
 * permitirselo. Lo que cambia es que ahora esa atribucion esta declarada -un
 * rol, comprobado en un solo lugar- en vez de ser el comportamiento por
 * omision para cualquiera que se hubiera autenticado.
 */
@Slf4j
@RestController
@RequestMapping("/api/web")
@RequiredArgsConstructor
public class CuentaWebController {

    private static final int TAMANO_PAGINA_MAXIMO = 200;

    /** Cuantos movimientos recientes acompanian al detalle de una cuenta. */
    private static final int MOVIMIENTOS_EN_DETALLE = 5;

    /** Rol que puede ver cuentas de las que no es titular. */
    private static final String ROL_CARTERA = "EJECUTIVO";

    private final ClienteCuentas cuentas;

    @Value("${bank.web.umbral-anomalia:2500}")
    private BigDecimal umbralAnomalia;

    /**
     * Listado completo de cuentas, con sus agregados ya resueltos.
     *
     * La cadena de seguridad ya exige rol EJECUTIVO para llegar aqui, asi que
     * no hay comprobacion por cuenta que hacer: no hay una cuenta pedida, hay
     * una cartera.
     *
     * Va sin movimientos recientes a proposito. Ver el javadoc de CuentaWebDto.
     */
    @GetMapping("/cuentas")
    public List<CuentaWebDto> listarCuentas() {
        return cuentas.listarCuentas().stream()
                .map(f -> aDto(f, List.of()))
                .toList();
    }

    /**
     * Ficha de una cuenta, con sus ultimos movimientos.
     *
     * Dos llamadas a ms-cuentas: la ficha y los movimientos. Se aceptan aqui
     * -y no en el listado- porque son dos viajes para una cuenta, no dos por
     * cada una de cincuenta.
     */
    @GetMapping("/cuentas/{cuentaId}")
    public ResponseEntity<CuentaWebDto> detalle(@PathVariable Long cuentaId,
                                                Authentication autenticacion) {

        if (!AutorizacionCuenta.puedeAcceder(autenticacion, cuentaId, ROL_CARTERA)) {
            return rechazar(autenticacion, cuentaId);
        }

        Optional<FichaCuenta> ficha = cuentas.ficha(cuentaId);
        if (ficha.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<MovimientoCuenta> recientes = cuentas.movimientos(cuentaId).orElseGet(List::of).stream()
                .sorted(Comparator.comparing(MovimientoCuenta::fecha).reversed())
                .limit(MOVIMIENTOS_EN_DETALLE)
                .toList();

        return ResponseEntity.ok(aDto(ficha.get(), recientes));
    }

    /**
     * Estado de cuenta anual con el desglose movimiento por movimiento.
     *
     * Es la respuesta mas pesada de los tres canales y solo existe aqui: en un
     * telefono nadie audita un anio de movimientos, y un cajero automatico no
     * tiene por que poder pedirlo.
     */
    @GetMapping("/cuentas/{cuentaId}/estado-anual")
    public ResponseEntity<EstadoAnualWebDto> estadoAnual(@PathVariable Long cuentaId,
                                                         @RequestParam(defaultValue = "2024") int anio,
                                                         Authentication autenticacion) {

        if (!AutorizacionCuenta.puedeAcceder(autenticacion, cuentaId, ROL_CARTERA)) {
            return rechazar(autenticacion, cuentaId);
        }

        Optional<FichaCuenta> ficha = cuentas.ficha(cuentaId);
        if (ficha.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<MovimientoCuenta> movimientos = cuentas.movimientos(cuentaId).orElseGet(List::of).stream()
                .filter(m -> m.fecha() != null && m.fecha().getYear() == anio)
                .sorted(Comparator.comparing(MovimientoCuenta::fecha))
                .toList();

        return ResponseEntity.ok(new EstadoAnualWebDto(
                cuentaId,
                ficha.get().nombre(),
                anio,
                totalPor(movimientos, "deposito"),
                totalPor(movimientos, "retiro"),
                totalPor(movimientos, "compra"),
                totalPor(movimientos, "deposito")
                        .subtract(totalPor(movimientos, "retiro"))
                        .subtract(totalPor(movimientos, "compra")),
                movimientos.size(),
                movimientos.stream().map(CuentaWebController::aDto).toList()));
    }

    /**
     * Transacciones paginadas por rango de fechas. Atribucion de ejecutivo.
     *
     * La tabla TRANSACCION no tiene columna de cuenta: es el libro de
     * operaciones del banco, no el extracto de un cliente. Por eso este
     * endpoint no se puede acotar al titular que pregunta y la cadena de
     * seguridad exige EJECUTIVO. En la entrega anterior bastaba con estar
     * autenticado, y cualquier cliente veia las operaciones de todos.
     *
     * El tamano de pagina es del cliente pero con techo: un navegador puede
     * pedir 200 filas sin problema, y dejarlo abierto permitiria que una
     * peticion se llevara las mil de golpe.
     */
    @GetMapping("/transacciones")
    public PaginaWebDto<TransaccionWebDto> transacciones(
            @RequestParam(defaultValue = "2024-01-01") LocalDate desde,
            @RequestParam(defaultValue = "2024-12-31") LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamano) {

        int tamanoEfectivo = Math.min(Math.max(tamano, 1), TAMANO_PAGINA_MAXIMO);
        PaginaTransacciones resultado = cuentas.transacciones(desde, hasta, pagina, tamanoEfectivo);

        return new PaginaWebDto<>(
                resultado.contenido().stream().map(this::aDto).toList(),
                resultado.pagina(),
                resultado.tamano(),
                resultado.totalElementos(),
                resultado.totalPaginas(),
                resultado.hayMas());
    }

    // ================================================================= MAPEO

    private CuentaWebDto aDto(FichaCuenta f, List<MovimientoCuenta> recientes) {
        return new CuentaWebDto(
                f.cuentaId(),
                f.nombre(),
                f.tipo(),
                f.saldoFinal(),
                formatear(f.saldoFinal()),
                f.edad(),
                segmento(f.edad()),
                f.cantidadMovimientos(),
                f.totalDepositos(),
                f.totalRetiros(),
                f.montoPromedio(),
                observacion(f),
                recientes.stream().map(CuentaWebController::aDto).toList());
    }

    private static MovimientoAnualWebDto aDto(MovimientoCuenta m) {
        return new MovimientoAnualWebDto(m.fecha(), m.tipoTransaccion(), m.monto(), m.descripcion());
    }

    private TransaccionWebDto aDto(TransaccionBanco t) {
        return new TransaccionWebDto(
                t.id(),
                t.fecha(),
                t.monto(),
                formatear(t.monto()),
                t.tipo(),
                esAnomalia(t),
                "desconocido".equals(t.tipo()));
    }

    /**
     * La marca de anomalia la calcula el batch de la Experiencia 1 y queda
     * guardada en la tabla. Cuando existe, manda: si cada BFF volviera a
     * decidirlo con un umbral propio, tres canales podrian discrepar sobre la
     * misma transaccion.
     *
     * El umbral local es solo el respaldo para cuando los datos se cargaron
     * desde los CSV y ningun batch los proceso, que es el modo con el que el
     * proyecto se ejecuta sin infraestructura. Ahi la columna viene en false
     * para todas las filas, y sin este respaldo la tabla no marcaria ninguna.
     */
    private boolean esAnomalia(TransaccionBanco t) {
        if (t.anomalia()) {
            return true;
        }
        return t.monto() != null && t.monto().compareTo(umbralAnomalia) > 0;
    }

    private static BigDecimal totalPor(List<MovimientoCuenta> movimientos, String tipo) {
        return movimientos.stream()
                .filter(m -> tipo.equalsIgnoreCase(m.tipoTransaccion()))
                .map(MovimientoCuenta::monto)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String formatear(BigDecimal monto) {
        return monto == null ? "-" : String.format(Locale.forLanguageTag("es-CL"), "$%,.0f", monto);
    }

    /**
     * Segmento etario para la interfaz. El dataset trae edades ausentes o fuera
     * de rango, que la carga deja en null; aqui se declaran como tales en vez
     * de asignarles un tramo inventado.
     */
    private static String segmento(Integer edad) {
        if (edad == null) {
            return "sin informacion";
        }
        if (edad < 30) {
            return "joven";
        }
        return edad < 60 ? "adulto" : "senior";
    }

    /** Aviso de calidad de dato, para que la interfaz pueda marcarlo. */
    private static String observacion(FichaCuenta f) {
        if ("desconocido".equals(f.tipo())) {
            return "tipo de cuenta no clasificado en el origen";
        }
        if (f.edad() == null) {
            return "edad ausente o fuera de rango en el origen";
        }
        return null;
    }

    /**
     * Cuenta ajena: 403 y sin cuerpo.
     *
     * No se devuelve 404 aunque la cuenta no exista, ni se dice si existe. La
     * respuesta es identica para una cuenta real que no le corresponde y para
     * una inventada, porque distinguirlas convertiria este endpoint en una
     * forma de averiguar que numeros de cuenta son validos.
     */
    private <T> ResponseEntity<T> rechazar(Authentication autenticacion, Long cuentaId) {
        log.warn("Acceso denegado: '{}' pidio la cuenta {} y no le corresponde",
                autenticacion == null ? "anonimo" : autenticacion.getName(), cuentaId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}

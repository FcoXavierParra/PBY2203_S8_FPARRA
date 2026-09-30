package cl.duoc.bank.bff.movil.api;

import cl.duoc.bank.bff.movil.dto.MovimientoMovilDto;
import cl.duoc.bank.bff.movil.dto.ResumenMovilDto;
import cl.duoc.bank.cliente.ClienteCuentas;
import cl.duoc.bank.contrato.FichaCuenta;
import cl.duoc.bank.contrato.MovimientoCuenta;
import cl.duoc.bank.seguridad.AutorizacionCuenta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * La API del canal movil: saldo y ultimos movimientos, nada mas.
 *
 * DOS FALLOS CORREGIDOS EN ESTE ARCHIVO
 * =====================================
 *
 * 1. CUALQUIER CUENTA ERA CONSULTABLE. Los dos endpoints tomaban el cuentaId
 *    del path y no lo cruzaban con nadie. Ahora se compara contra la cuenta
 *    enrolada que viaja firmada en el token. Ver AutorizacionCuenta.
 *
 * 2. LOS MOVIMIENTOS ERAN DE OTRA GENTE. El metodo que los resolvia devolvia
 *    consulta.ultimasTransacciones(), es decir, las ultimas transacciones del
 *    BANCO. La tabla TRANSACCION no tiene columna de cuenta, asi que esa
 *    consulta no podia estar filtrada por titular: el endpoint
 *    /cuentas/{id}/movimientos comprobaba que la cuenta existiera y despues
 *    devolvia operaciones que no eran suyas.
 *
 *    Los movimientos por cuenta viven en MOVIMIENTO_ANUAL, que si tiene
 *    cuenta_id, y son los que ms-cuentas expone en /interno/cuentas/{id}/
 *    movimientos. Era la consulta que correspondia desde el principio.
 *
 * POR QUE EL cuentaId SIGUE EN LA URL SI YA VIENE EN EL TOKEN
 * ===========================================================
 * Podria sacarse y dejar /api/movil/resumen a secas, que es como opera el
 * canal cajero. Se mantiene en la ruta por dos razones: el recurso se
 * identifica por su nombre completo, que es lo que corresponde en una API
 * REST, y sobre todo porque permite DEMOSTRAR la correccion. Un endpoint sin
 * parametro es seguro porque no hay nada que falsificar; uno con parametro y
 * una comprobacion explicita se puede probar, y la evidencia de esta entrega
 * pide justamente la cuenta ajena y muestra el 403.
 */
@Slf4j
@RestController
@RequestMapping("/api/movil")
@RequiredArgsConstructor
public class ResumenMovilController {

    /** Tope fijo del servidor. Una pantalla de telefono no muestra mas. */
    private static final int MAXIMO_MOVIMIENTOS = 5;

    private final ClienteCuentas cuentas;

    /** Pantalla de inicio: saldo y ultimos movimientos, en una sola llamada. */
    @GetMapping("/cuentas/{cuentaId}/resumen")
    public ResponseEntity<ResumenMovilDto> resumen(@PathVariable Long cuentaId,
                                                   Authentication autenticacion) {

        if (!AutorizacionCuenta.puedeAcceder(autenticacion, cuentaId)) {
            return rechazar(autenticacion, cuentaId);
        }

        Optional<FichaCuenta> ficha = cuentas.ficha(cuentaId);
        if (ficha.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(new ResumenMovilDto(
                ficha.get().cuentaId(),
                ficha.get().saldoFinal(),
                "CLP",
                ultimosDe(cuentaId)));
    }

    /** Ultimos movimientos DE ESTA CUENTA. */
    @GetMapping("/cuentas/{cuentaId}/movimientos")
    public ResponseEntity<List<MovimientoMovilDto>> movimientos(@PathVariable Long cuentaId,
                                                                Authentication autenticacion) {

        if (!AutorizacionCuenta.puedeAcceder(autenticacion, cuentaId)) {
            return rechazar(autenticacion, cuentaId);
        }

        Optional<List<MovimientoCuenta>> movimientos = cuentas.movimientos(cuentaId);
        return movimientos
                .map(lista -> ResponseEntity.ok(recortar(lista)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ================================================================= MAPEO

    private List<MovimientoMovilDto> ultimosDe(Long cuentaId) {
        return recortar(cuentas.movimientos(cuentaId).orElseGet(List::of));
    }

    private static List<MovimientoMovilDto> recortar(List<MovimientoCuenta> movimientos) {
        return movimientos.stream()
                .sorted(Comparator.comparing(MovimientoCuenta::fecha,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAXIMO_MOVIMIENTOS)
                .map(ResumenMovilController::aDto)
                .toList();
    }

    /**
     * Tres campos, sin formatear.
     *
     * El canal web devuelve el mismo movimiento con el monto ya escrito en
     * pesos y con marcas de anomalia; una aplicacion movil formatea segun la
     * configuracion regional del telefono, asi que mandarle texto formateado
     * seria mandarle bytes que va a descartar.
     */
    private static MovimientoMovilDto aDto(MovimientoCuenta m) {
        return new MovimientoMovilDto(m.fecha(), m.monto(), m.tipoTransaccion());
    }

    /**
     * Cuenta ajena: 403 y sin cuerpo, igual que en el canal web y por el mismo
     * motivo. La respuesta no distingue entre una cuenta real que no le
     * corresponde y una inventada.
     */
    private <T> ResponseEntity<T> rechazar(Authentication autenticacion, Long cuentaId) {
        log.warn("Acceso denegado: el dispositivo '{}' pidio la cuenta {} y no le corresponde",
                autenticacion == null ? "anonimo" : autenticacion.getName(), cuentaId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}

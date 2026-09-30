package cl.duoc.bank.bff.cajero.api;

import cl.duoc.bank.bff.cajero.config.SesionCajeroService;
import cl.duoc.bank.bff.cajero.dto.OperacionDto;
import cl.duoc.bank.bff.cajero.dto.SesionDto;
import cl.duoc.bank.cliente.ClienteCuentas;
import cl.duoc.bank.contrato.FichaCuenta;
import cl.duoc.bank.contrato.ResultadoRetiro;
import cl.duoc.bank.seguridad.AutorizacionCuenta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * La API del cajero automatico: dos campos de saldo y un retiro.
 *
 * QUE CAMBIO EN ESTA ENTREGA
 * ==========================
 * Los datos y la operacion viajan ahora por HTTP hacia ms-cuentas en vez de
 * resolverse en proceso. Para las consultas eso es rutina; para el retiro
 * introduce un problema que antes no existia y que se aborda abajo.
 *
 * La autorizacion por cuenta no cambio porque este canal ya la hacia bien:
 * la cuenta salio siempre del token. Lo unico que cambio es que ahora se lee
 * con AutorizacionCuenta, el mismo mecanismo que usan los otros dos canales.
 *
 * EL RETIRO NO SE REINTENTA, Y ESA ES LA DECISION IMPORTANTE
 * ==========================================================
 * Mientras la llamada al dominio era un metodo en el mismo proceso, "no se
 * cual fue el resultado" no era un estado posible: o la operacion se aplico y
 * volvio, o lanzo una excepcion. Con HTTP de por medio aparece un tercer caso:
 * la operacion se aplico en ms-cuentas y la respuesta se perdio en el camino.
 *
 * Si ese caso se reintentara, el cliente recibiria el dinero una vez y se le
 * descontaria dos. Por eso ClienteCuentas.retirar no lleva \@Retry y este
 * controlador, ante una falla, le dice a la persona que verifique su saldo
 * antes de volver a intentar, en vez de reintentar por ella.
 *
 * La solucion completa es una clave de idempotencia: el cajero generaria un
 * identificador por operacion, ms-cuentas recordaria cuales ya aplico y un
 * reintento con la misma clave devolveria el resultado original en vez de
 * descontar de nuevo. Queda fuera del alcance de esta semana y esta anotado
 * como tal, porque implementarla a medias es peor que no tenerla.
 */
@Slf4j
@RestController
@RequestMapping("/api/cajero")
@RequiredArgsConstructor
public class CajeroController {

    /** Denominacion minima que entrega el dispensador. */
    private static final BigDecimal MULTIPLO = new BigDecimal("10000");

    /** Tope por operacion del canal, independiente del saldo disponible. */
    private static final BigDecimal TOPE_POR_OPERACION = new BigDecimal("200000");

    private final ClienteCuentas cuentas;
    private final SesionCajeroService sesiones;

    /**
     * Tarjeta y PIN a cambio de una sesion de dos minutos en ESTE terminal.
     *
     * Solo lo puede llamar un terminal autenticado con su token OAuth 2.0
     * (scope cajero.terminal, ver SeguridadCajeroConfig): el nombre de esa
     * autenticacion es el cliente del auth-server -cajero-terminal-01-, y la
     * sesion queda atada a el.
     *
     * Un PIN incorrecto y una cuenta inexistente devuelven exactamente la
     * misma respuesta 401. Distinguirlas le diria a quien prueba tarjetas al
     * azar cuales existen, que es la mitad del trabajo de un ataque.
     */
    @PostMapping("/sesion")
    public ResponseEntity<SesionDto.Respuesta> abrirSesion(@Valid @RequestBody SesionDto.Solicitud solicitud,
                                                           Authentication terminal) {

        Optional<FichaCuenta> cuenta = cuentas.ficha(solicitud.cuentaId());
        if (cuenta.isEmpty() || !sesiones.pinCorrecto(solicitud.pin())) {
            log.warn("Intento de sesion rechazado en {} para la tarjeta {}", terminal.getName(), solicitud.cuentaId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // La cuenta queda guardada de este lado, junto al terminal. Lo que
        // recibe el cajero es un identificador que no contiene nada: no puede
        // cambiar la cuenta sobre la que opera porque no la tiene.
        String sesion = sesiones.abrir(solicitud.cuentaId(), terminal.getName());
        return ResponseEntity.ok(new SesionDto.Respuesta(sesion, sesiones.duracionSegundos()));
    }

    /**
     * Saldo disponible. Un campo mas la moneda.
     *
     * La cuenta sale del token de sesion, no de la peticion: el terminal no
     * puede pedir el saldo de una cuenta distinta de la que abrio sesion.
     */
    @GetMapping("/saldo")
    public ResponseEntity<OperacionDto.Saldo> saldo(Authentication autenticacion) {
        Long cuentaId = AutorizacionCuenta.cuentaDe(autenticacion);
        if (cuentaId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return cuentas.ficha(cuentaId)
                .map(c -> ResponseEntity.ok(new OperacionDto.Saldo(c.saldoFinal(), "CLP")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Retiro de efectivo. */
    @PostMapping("/retiro")
    public ResponseEntity<OperacionDto.ResultadoRetiro> retirar(
            @Valid @RequestBody OperacionDto.SolicitudRetiro solicitud,
            Authentication autenticacion) {

        Long cuentaId = AutorizacionCuenta.cuentaDe(autenticacion);
        String sesion = (String) autenticacion.getCredentials();
        BigDecimal monto = solicitud.monto();

        if (cuentaId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // --- Reglas del canal, antes de salir a la red --------------------
        // Se comprueban aqui y no en ms-cuentas porque son politica del punto
        // de atencion: un cajero entrega billetes, la web no tiene esa
        // restriccion. Ademas ahorran un viaje: un monto no dispensable se
        // rechaza sin molestar al Backend.
        if (monto.remainder(MULTIPLO).compareTo(BigDecimal.ZERO) != 0) {
            return ResponseEntity.badRequest().body(new OperacionDto.ResultadoRetiro(
                    false, "MONTO_NO_DISPENSABLE",
                    "El monto debe ser multiplo de " + MULTIPLO.toPlainString(),
                    null, null));
        }
        if (monto.compareTo(TOPE_POR_OPERACION) > 0) {
            return ResponseEntity.badRequest().body(new OperacionDto.ResultadoRetiro(
                    false, "SOBRE_TOPE",
                    "El tope por operacion es " + TOPE_POR_OPERACION.toPlainString(),
                    null, null));
        }

        // --- Regla del banco: que el saldo alcance, de forma atomica -------
        ResultadoRetiro resultado = cuentas.retirar(cuentaId, monto);

        if (!resultado.autorizado()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new OperacionDto.ResultadoRetiro(
                    false, "RECHAZADO", resultado.motivo(), null, resultado.saldoResultante()));
        }

        // La sesion se cierra al completar la operacion: el cliente ya retiro
        // su tarjeta y no hay razon para dejar el token vivo el resto de su
        // ventana.
        sesiones.cerrar(sesion);

        return ResponseEntity.ok(new OperacionDto.ResultadoRetiro(
                true, "AUTORIZADO", "Retire su dinero", monto, resultado.saldoResultante()));
    }
}

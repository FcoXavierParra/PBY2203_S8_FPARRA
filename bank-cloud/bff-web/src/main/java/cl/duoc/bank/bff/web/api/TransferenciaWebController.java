package cl.duoc.bank.bff.web.api;

import cl.duoc.bank.bff.web.dto.TransferenciaWebDto;
import cl.duoc.bank.cliente.ClienteTransferencias;
import cl.duoc.bank.contrato.EstadoTransferencia;
import cl.duoc.bank.contrato.SolicitudTransferencia;
import cl.duoc.bank.seguridad.AutorizacionCuenta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Transferencias desde el canal web. Nuevo en la semana 7.
 *
 * Es el punto de entrada de la saga para una persona. Lo que hace este
 * controlador es decidir QUIEN transfiere; todo lo demas -cupo, saldo,
 * compensacion- ocurre detras, por eventos.
 *
 * AUTORIZACION: EL ORIGEN SALE DEL TOKEN
 * ======================================
 * La cuenta de origen no esta en el cuerpo de la peticion. Si estuviera, el
 * usuario 'cliente' podria transferir desde la cuenta 107 escribiendo 107 en
 * el JSON: el mismo IDOR que costo el criterio de seguridad en la semana 5,
 * solo que esta vez moviendo dinero en vez de leyendolo.
 *
 * Y al consultar una transferencia se comprueba que su origen sea la cuenta
 * del token. El identificador es un UUID imposible de adivinar, pero "dificil
 * de adivinar" no es un control de acceso: un UUID aparece en logs, en
 * historiales del navegador y en capturas de pantalla.
 *
 * LA CLAVE DE IDEMPOTENCIA SE ACOTA A LA CUENTA
 * =============================================
 * El navegador puede mandar su propia Idempotency-Key -para que un doble clic
 * no genere dos transferencias- y si no la manda, la genera este BFF. En los
 * dos casos se le antepone la cuenta del titular antes de enviarla. Sin eso,
 * dos usuarios que eligieran la misma clave chocarian, y peor: el segundo
 * recibiria como respuesta la transferencia del primero, porque para
 * ms-transferencias seria una repeticion.
 */
@Slf4j
@RestController
@RequestMapping("/api/web/transferencias")
@RequiredArgsConstructor
public class TransferenciaWebController {

    private static final String ROL_CARTERA = "EJECUTIVO";

    private final ClienteTransferencias transferencias;

    @PostMapping
    public ResponseEntity<?> transferir(
            @Valid @RequestBody TransferenciaWebDto.Solicitud solicitud,
            @RequestHeader(name = "Idempotency-Key", required = false) String claveCliente,
            Authentication autenticacion) {

        Long origen = AutorizacionCuenta.cuentaDe(autenticacion);
        if (origen == null) {
            // El ejecutivo recorre la cartera pero no es titular de ninguna
            // cuenta: puede consultar, no transferir en nombre de otro.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new TransferenciaWebDto.Rechazo(
                    "SIN_CUENTA_PROPIA", "Solo el titular de una cuenta puede transferir desde ella"));
        }

        String clave = origen + ":" + (claveCliente != null && !claveCliente.isBlank()
                ? claveCliente
                : UUID.randomUUID().toString());

        ClienteTransferencias.Respuesta r = transferencias.solicitar(
                new SolicitudTransferencia(origen, solicitud.cuentaDestino(), solicitud.monto()), clave);

        if (!r.fueAceptada()) {
            return ResponseEntity.unprocessableEntity()
                    .body(new TransferenciaWebDto.Rechazo(r.codigoRechazo(), r.mensajeRechazo()));
        }

        TransferenciaWebDto.Estado e = aDto(r.aceptada());
        return ResponseEntity.accepted().location(URI.create(e.consultarEn())).body(e);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> estado(@PathVariable String id, Authentication autenticacion) {
        Optional<EstadoTransferencia> e = transferencias.estado(id);
        if (e.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!AutorizacionCuenta.puedeAcceder(autenticacion, e.get().cuentaOrigen(), ROL_CARTERA)) {
            log.warn("Consulta de la transferencia {} rechazada: su origen no es la cuenta del token", id);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(aDto(e.get()));
    }

    private static TransferenciaWebDto.Estado aDto(EstadoTransferencia t) {
        return new TransferenciaWebDto.Estado(t.transferenciaId(), t.estado(), t.cuentaDestino(), t.monto(),
                t.motivo(), t.creadaEn(), t.actualizadaEn(), "/api/web/transferencias/" + t.transferenciaId());
    }
}

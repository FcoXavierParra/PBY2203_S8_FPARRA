package cl.duoc.bank.core.servicio;

import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.EventoProcesado;
import cl.duoc.bank.core.repositorio.CuentaRepository;
import cl.duoc.bank.core.repositorio.EventoProcesadoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * El paso local de ms-cuentas en la saga de transferencias.
 *
 * QUE ES UNA TRANSACCION LOCAL AQUI
 * =================================
 * Debitar el origen, acreditar el destino y anotar el evento como procesado
 * ocurren en UNA transaccion de base de datos: o pasan las tres cosas o
 * ninguna. Es el unico punto de la saga donde hay atomicidad clasica, y es
 * suficiente porque las dos cuentas viven en la misma base.
 *
 * Lo que NO puede ser atomico es esto con el cupo diario, que vive en la base
 * de ms-transferencias. Para eso esta la saga: cada servicio confirma su parte
 * por separado y, si la de aqui falla, ms-transferencias compensa la suya.
 *
 * ORDEN DE LOS BLOQUEOS
 * =====================
 * Las dos cuentas se bloquean siempre de menor a mayor numero, sin importar
 * cual es origen y cual destino. Si una instancia procesa 105 -> 107 y la otra
 * 107 -> 105 al mismo tiempo, y cada una bloqueara primero su origen, cada una
 * quedaria esperando la cuenta que tiene la otra: un deadlock. Con un orden
 * global, la segunda espera a la primera y ya.
 *
 * Esta clase no sabe que existe JMS: recibe numeros y devuelve un resultado.
 * Traducir eso a eventos es trabajo del listener de ms-cuentas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferenciaService {

    private final CuentaRepository cuentas;
    private final EventoProcesadoRepository procesados;

    /**
     * @param duplicado true si el evento ya se habia procesado. En ese caso no
     *                  se toco ningun saldo y los demas campos son los de la
     *                  primera vez.
     */
    public record Resultado(
            boolean aplicada,
            boolean duplicado,
            String motivo,
            BigDecimal saldoOrigen,
            BigDecimal saldoDestino,
            String respuestaId) {
    }

    @Transactional
    public Resultado aplicar(String eventoId, String transferenciaId,
                             Long origen, Long destino, BigDecimal monto, String instancia) {

        // --- Idempotencia: si ya se proceso, se devuelve lo mismo que entonces.
        Optional<EventoProcesado> previo = procesados.findById(eventoId);
        if (previo.isPresent()) {
            EventoProcesado p = previo.get();
            log.warn("Evento {} ya procesado por {}: duplicado ignorado, no se toca ningun saldo",
                    eventoId, p.getProcesadoPor());
            return new Resultado(p.isAplicada(), true, p.getMotivo(),
                    p.getSaldoOrigen(), p.getSaldoDestino(), p.getRespuestaId());
        }

        // --- Validaciones que no necesitan leer la base
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return rechazar(eventoId, transferenciaId, "El monto debe ser mayor que cero", instancia);
        }
        if (origen == null || origen.equals(destino)) {
            return rechazar(eventoId, transferenciaId, "Origen y destino deben ser cuentas distintas", instancia);
        }

        // --- Bloqueo en orden global. Ver javadoc de la clase.
        Long primera = Math.min(origen, destino);
        Long segunda = Math.max(origen, destino);
        Optional<Cuenta> a = cuentas.bloquear(primera);
        Optional<Cuenta> b = cuentas.bloquear(segunda);
        Optional<Cuenta> cOrigen = origen.equals(primera) ? a : b;
        Optional<Cuenta> cDestino = origen.equals(primera) ? b : a;

        if (cOrigen.isEmpty()) {
            return rechazar(eventoId, transferenciaId, "Cuenta de origen inexistente", instancia);
        }
        if (cDestino.isEmpty()) {
            return rechazar(eventoId, transferenciaId, "Cuenta de destino inexistente", instancia);
        }

        Cuenta o = cOrigen.get();
        Cuenta d = cDestino.get();
        if (o.getSaldoFinal() == null || d.getSaldoFinal() == null) {
            return rechazar(eventoId, transferenciaId, "Una de las cuentas no tiene saldo registrado", instancia);
        }
        if (o.getSaldoFinal().compareTo(monto) < 0) {
            return rechazar(eventoId, transferenciaId, "Saldo insuficiente", instancia);
        }

        // --- La operacion
        o.setSaldoFinal(o.getSaldoFinal().subtract(monto));
        d.setSaldoFinal(d.getSaldoFinal().add(monto));
        cuentas.save(o);
        cuentas.save(d);

        String respuestaId = UUID.randomUUID().toString();
        procesados.save(new EventoProcesado(eventoId, transferenciaId, true, null,
                o.getSaldoFinal(), d.getSaldoFinal(), respuestaId, instancia, Instant.now()));

        log.info("Transferencia {} aplicada por {}: {} -> {} por {} (saldo origen {})",
                transferenciaId, instancia, origen, destino, monto, o.getSaldoFinal());
        return new Resultado(true, false, null, o.getSaldoFinal(), d.getSaldoFinal(), respuestaId);
    }

    /**
     * Un rechazo tambien se anota como procesado. Si el mismo evento llegara
     * de nuevo -y entre medio el origen hubiera recibido un deposito-, sin esta
     * fila la segunda entrega podria aplicarse: la misma solicitud tendria dos
     * respuestas distintas, y ms-transferencias ya habria compensado por la
     * primera.
     */
    private Resultado rechazar(String eventoId, String transferenciaId, String motivo, String instancia) {
        String respuestaId = UUID.randomUUID().toString();
        procesados.save(new EventoProcesado(eventoId, transferenciaId, false, motivo,
                null, null, respuestaId, instancia, Instant.now()));
        log.info("Transferencia {} rechazada por {}: {}", transferenciaId, instancia, motivo);
        return new Resultado(false, false, motivo, null, null, respuestaId);
    }
}

package cl.duoc.bank.transferencias.servicio;

import cl.duoc.bank.contrato.EstadoTransferencia;
import cl.duoc.bank.contrato.SolicitudTransferencia;
import cl.duoc.bank.contrato.eventos.EventoTransferencia;
import cl.duoc.bank.contrato.eventos.Topicos;
import cl.duoc.bank.contrato.eventos.TransferenciaAplicada;
import cl.duoc.bank.contrato.eventos.TransferenciaCerrada;
import cl.duoc.bank.contrato.eventos.TransferenciaRechazada;
import cl.duoc.bank.contrato.eventos.TransferenciaSolicitada;
import cl.duoc.bank.transferencias.dominio.CupoDiario;
import cl.duoc.bank.transferencias.dominio.EventoSaliente;
import cl.duoc.bank.transferencias.dominio.Repositorios;
import cl.duoc.bank.transferencias.dominio.Transferencia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * La saga de transferencias, vista desde el servicio que la inicia.
 *
 * <pre>
 *   ms-transferencias            broker              ms-cuentas
 *   ─────────────────            ──────              ──────────
 *   1. reserva cupo, PENDIENTE
 *      + outbox ──────────► .solicitada ──────────►  2. debita y acredita
 *                                                       (transaccion local)
 *   3a. COMPLETADA     ◄────── .aplicada  ◄──────────  si alcanzo el saldo
 *   3b. RECHAZADA      ◄────── .rechazada ◄──────────  si no
 *       + COMPENSACION: devuelve el cupo reservado en 1
 *   4. + outbox ──────────► .cerrada   ──────────►  ms-auditoria
 * </pre>
 *
 * POR QUE COREOGRAFIA Y NO ORQUESTACION
 * =====================================
 * La guia presenta las dos. En una orquestacion, este servicio le ORDENARIA a
 * ms-cuentas "debita" y esperaria la respuesta: seria duenio del flujo
 * completo y conoceria a cada participante. Aqui solo anuncia un hecho -se
 * solicito una transferencia- y reacciona a otros hechos. No sabe que existe
 * ms-cuentas ni ms-auditoria, y agregar un tercer interesado -un servicio de
 * notificaciones, un detector de fraude- es suscribirlo a un topico, sin tocar
 * una linea de aqui.
 *
 * El costo de la coreografia es que el flujo no esta escrito en ningun lugar
 * del codigo: esta repartido entre servicios. Con dos participantes y tres
 * pasos es facil de seguir; con seis participantes seria la razon para pasar
 * a un orquestador. Por eso el diagrama del README y ms-auditoria existen: son
 * donde el flujo SI se ve completo.
 *
 * LA COMPENSACION
 * ===============
 * Una saga no se deshace con rollback: el paso 1 ya se confirmo en esta base
 * cuando ms-cuentas decide que no. Se deshace con una accion de negocio que
 * revierte el efecto -devolver el cupo-, y queda registrada como un paso mas,
 * no borrada. La auditoria muestra la reserva y la devolucion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaTransferencias {

    private static final ZoneId ZONA = ZoneId.of("America/Santiago");

    private final Repositorios.Transferencias transferencias;
    private final Repositorios.Cupos cupos;
    private final Repositorios.Salientes salientes;
    private final ObjectMapper json;

    @Value("${bank.transferencias.cupo-diario}")
    private BigDecimal cupoDiario;

    /** Una solicitud que no pasa las reglas de este servicio. No inicia saga. */
    public static class SolicitudRechazada extends RuntimeException {
        private final String codigo;

        public SolicitudRechazada(String codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }

        public String getCodigo() {
            return codigo;
        }
    }

    // ================================================================ PASO 1

    /**
     * Acepta una solicitud, reserva el cupo y deja el evento en el outbox. Todo
     * en una transaccion: o queda la transferencia con su reserva y su evento,
     * o no queda nada.
     *
     * Lo que se rechaza aqui -monto invalido, misma cuenta, cupo excedido- se
     * rechaza de inmediato y sin saga: son reglas que este servicio puede
     * decidir solo, y no tiene sentido publicar un evento para enterarse de
     * algo que ya se sabe.
     *
     * @param clave la Idempotency-Key del cliente, o null
     */
    @Transactional
    public EstadoTransferencia solicitar(SolicitudTransferencia s, String clave) {

        if (clave != null) {
            Optional<Transferencia> previa = transferencias.findByClaveIdempotencia(clave);
            if (previa.isPresent()) {
                log.info("Idempotency-Key {} repetida: se devuelve la transferencia {} sin crear otra",
                        clave, previa.get().getId());
                return aEstado(previa.get());
            }
        }

        if (s.monto() == null || s.monto().compareTo(BigDecimal.ZERO) <= 0) {
            throw new SolicitudRechazada("MONTO_INVALIDO", "El monto debe ser mayor que cero");
        }
        if (s.cuentaOrigen() == null || s.cuentaDestino() == null) {
            throw new SolicitudRechazada("CUENTA_REQUERIDA", "Origen y destino son obligatorios");
        }
        if (s.cuentaOrigen().equals(s.cuentaDestino())) {
            throw new SolicitudRechazada("MISMA_CUENTA", "Origen y destino deben ser cuentas distintas");
        }

        // --- Reserva del cupo, con la fila bloqueada
        LocalDate hoy = LocalDate.now(ZONA);
        String idCupo = CupoDiario.clave(s.cuentaOrigen(), hoy);
        CupoDiario cupo = cupos.bloquear(idCupo)
                .orElseGet(() -> cupos.save(new CupoDiario(idCupo, s.cuentaOrigen(), hoy, BigDecimal.ZERO)));

        BigDecimal disponible = cupoDiario.subtract(cupo.getReservado());
        if (s.monto().compareTo(disponible) > 0) {
            throw new SolicitudRechazada("CUPO_EXCEDIDO",
                    "El monto supera el cupo diario disponible (" + disponible.toPlainString() + ")");
        }
        cupo.setReservado(cupo.getReservado().add(s.monto()));

        // --- La transferencia
        Instant ahora = Instant.now();
        Transferencia t = new Transferencia();
        t.setId(UUID.randomUUID().toString());
        t.setClaveIdempotencia(clave);
        t.setCuentaOrigen(s.cuentaOrigen());
        t.setCuentaDestino(s.cuentaDestino());
        t.setMonto(s.monto());
        t.setEstado(Transferencia.Estado.PENDIENTE.name());
        t.setFecha(hoy);
        t.setCreadaEn(ahora);
        t.setActualizadaEn(ahora);
        transferencias.save(t);

        // --- El evento, al outbox y no al broker
        encolar(Topicos.TRANSFERENCIA_SOLICITADA, new TransferenciaSolicitada(
                UUID.randomUUID().toString(), t.getId(), t.getCuentaOrigen(), t.getCuentaDestino(),
                t.getMonto(), ahora));

        log.info("Transferencia {} aceptada: {} -> {} por {}. Cupo reservado {} de {}",
                t.getId(), t.getCuentaOrigen(), t.getCuentaDestino(), t.getMonto(),
                cupo.getReservado(), cupoDiario);
        return aEstado(t);
    }

    // ============================================================= PASO 3a

    @Transactional
    public void alAplicarse(TransferenciaAplicada e) {
        Optional<Transferencia> o = pendiente(e.transferenciaId(), "TransferenciaAplicada");
        if (o.isEmpty()) {
            return;
        }
        Transferencia t = o.get();
        cerrar(t, Transferencia.Estado.COMPLETADA, null);
        encolar(Topicos.TRANSFERENCIA_CERRADA, new TransferenciaCerrada(
                UUID.randomUUID().toString(), t.getId(), t.getEstado(), null, BigDecimal.ZERO, Instant.now()));
        log.info("Saga {} COMPLETADA (aplicada por {})", t.getId(), e.procesadoPor());
    }

    // ============================================================= PASO 3b

    @Transactional
    public void alRechazarse(TransferenciaRechazada e) {
        Optional<Transferencia> o = pendiente(e.transferenciaId(), "TransferenciaRechazada");
        if (o.isEmpty()) {
            return;
        }
        Transferencia t = o.get();

        // --- COMPENSACION: se devuelve el cupo que reservo el paso 1.
        CupoDiario cupo = cupos.bloquear(CupoDiario.clave(t.getCuentaOrigen(), t.getFecha()))
                .orElseThrow(() -> new IllegalStateException("Transferencia sin cupo reservado: " + t.getId()));
        cupo.setReservado(cupo.getReservado().subtract(t.getMonto()));

        cerrar(t, Transferencia.Estado.RECHAZADA, e.motivo());
        encolar(Topicos.TRANSFERENCIA_CERRADA, new TransferenciaCerrada(
                UUID.randomUUID().toString(), t.getId(), t.getEstado(), e.motivo(), t.getMonto(), Instant.now()));
        log.info("Saga {} RECHAZADA ({}). Compensacion: cupo de la cuenta {} liberado en {}, queda reservado {}",
                t.getId(), e.motivo(), t.getCuentaOrigen(), t.getMonto(), cupo.getReservado());
    }

    // ============================================================= CONSULTA

    @Transactional(readOnly = true)
    public Optional<EstadoTransferencia> estado(String id) {
        return transferencias.findById(id).map(SagaTransferencias::aEstado);
    }

    @Transactional(readOnly = true)
    public BigDecimal reservadoHoy(Long cuenta) {
        return cupos.findById(CupoDiario.clave(cuenta, LocalDate.now(ZONA)))
                .map(CupoDiario::getReservado)
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal cupoDiario() {
        return cupoDiario;
    }

    // ============================================================== SOPORTE

    /**
     * La transferencia, solo si sigue PENDIENTE. Si ya se cerro, el resultado
     * es un duplicado -o llego tarde- y se ignora: la saga ya tomo su decision
     * y no se reabre.
     */
    private Optional<Transferencia> pendiente(String id, String tipo) {
        Optional<Transferencia> t = transferencias.findById(id);
        if (t.isEmpty()) {
            log.warn("{} para una transferencia desconocida ({}); se ignora", tipo, id);
            return Optional.empty();
        }
        if (!t.get().estaPendiente()) {
            log.warn("{} para la transferencia {} que ya esta {}: duplicado ignorado",
                    tipo, id, t.get().getEstado());
            return Optional.empty();
        }
        return t;
    }

    private void cerrar(Transferencia t, Transferencia.Estado estado, String motivo) {
        t.setEstado(estado.name());
        t.setMotivo(motivo);
        t.setActualizadaEn(Instant.now());
        transferencias.save(t);
    }

    private void encolar(String topico, EventoTransferencia evento) {
        EventoSaliente fila = new EventoSaliente();
        fila.setEventoId(evento.eventoId());
        fila.setTopico(topico);
        fila.setTipo(evento.getClass().getSimpleName());
        fila.setTransferenciaId(evento.transferenciaId());
        try {
            fila.setPayload(json.writeValueAsString(evento));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar " + evento, ex);
        }
        fila.setCreadoEn(Instant.now());
        salientes.save(fila);
    }

    static EstadoTransferencia aEstado(Transferencia t) {
        return new EstadoTransferencia(t.getId(), t.getCuentaOrigen(), t.getCuentaDestino(), t.getMonto(),
                t.getEstado(), t.getMotivo(), t.getCreadaEn(), t.getActualizadaEn());
    }
}

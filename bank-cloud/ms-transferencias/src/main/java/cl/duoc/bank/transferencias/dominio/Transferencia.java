package cl.duoc.bank.transferencias.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una transferencia, y en que punto de la saga esta.
 *
 * <pre>
 *                  TransferenciaAplicada
 *   PENDIENTE  ─────────────────────────►  COMPLETADA
 *       │
 *       │          TransferenciaRechazada
 *       └──────────────────────────────►  RECHAZADA   (+ compensacion: se libera el cupo)
 * </pre>
 *
 * Solo se sale de PENDIENTE, y una sola vez. Es la idempotencia de este lado:
 * si el resultado llega dos veces, la segunda encuentra la transferencia ya
 * cerrada y no hace nada. El @Version cubre el caso de que las dos lleguen a
 * la vez: la segunda falla al confirmar, se reintenta y cae en el primer caso.
 */
@Entity
@Table(name = "transferencia")
@Data
@NoArgsConstructor
public class Transferencia {

    public enum Estado { PENDIENTE, COMPLETADA, RECHAZADA }

    @Id
    @Column(length = 36)
    private String id;

    /**
     * La que manda el cliente en la cabecera Idempotency-Key. Unica: la misma
     * clave dos veces devuelve la misma transferencia en vez de crear otra.
     */
    @Column(name = "clave_idempotencia", length = 64, unique = true)
    private String claveIdempotencia;

    @Column(name = "cuenta_origen", nullable = false)
    private Long cuentaOrigen;

    @Column(name = "cuenta_destino", nullable = false)
    private Long cuentaDestino;

    @Column(nullable = false)
    private BigDecimal monto;

    @Column(length = 16, nullable = false)
    private String estado;

    private String motivo;

    /** Dia al que se imputa el cupo. */
    @Column(nullable = false)
    private LocalDate fecha;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "actualizada_en", nullable = false)
    private Instant actualizadaEn;

    @Version
    private Long version;

    public boolean estaPendiente() {
        return Estado.PENDIENTE.name().equals(estado);
    }
}

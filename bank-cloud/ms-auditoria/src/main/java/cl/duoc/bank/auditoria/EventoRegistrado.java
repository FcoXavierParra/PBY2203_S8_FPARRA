package cl.duoc.bank.auditoria;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Un evento, tal como llego. Nunca se modifica ni se borra: no hay un solo
 * UPDATE ni DELETE sobre esta tabla en todo el servicio.
 *
 * secuencia es el orden de llegada a ESTE servicio. ocurridoEn es cuando paso
 * el hecho segun quien lo publico. Pueden no coincidir -dos instancias de
 * ms-cuentas publican en paralelo- y por eso se guardan los dos.
 */
@Entity
@Table(name = "evento_registrado", indexes = @Index(name = "ix_evento_saga", columnList = "transferencia_id"))
@Data
@NoArgsConstructor
public class EventoRegistrado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long secuencia;

    /** Unico: un mismo evento entregado dos veces se registra una sola vez. */
    @Column(name = "evento_id", length = 36, nullable = false, unique = true)
    private String eventoId;

    @Column(nullable = false, length = 40)
    private String tipo;

    @Column(nullable = false, length = 80)
    private String topico;

    @Column(name = "transferencia_id", length = 36, nullable = false)
    private String transferenciaId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    @Column(name = "recibido_en", nullable = false)
    private Instant recibidoEn;

    public interface Repositorio extends JpaRepository<EventoRegistrado, Long> {

        boolean existsByEventoId(String eventoId);

        List<EventoRegistrado> findByTransferenciaIdOrderBySecuenciaAsc(String transferenciaId);

        long countByTipo(String tipo);
    }
}

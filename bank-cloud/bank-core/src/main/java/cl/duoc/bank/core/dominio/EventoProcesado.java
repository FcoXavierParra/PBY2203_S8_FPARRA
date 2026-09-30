package cl.duoc.bank.core.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un evento que ms-cuentas ya proceso, y lo que respondio.
 *
 * ES LA IDEMPOTENCIA DEL CONSUMIDOR
 * =================================
 * El broker entrega at-least-once: si ms-cuentas aplica una transferencia y
 * muere antes de confirmar el mensaje, el broker se lo vuelve a entregar -a
 * el o a la otra instancia-. Sin esta tabla, la transferencia se aplicaria dos
 * veces. Es la estrategia que la guia llama "persistencia en el consumidor".
 *
 * La fila se inserta EN LA MISMA TRANSACCION que mueve los saldos. Esa es la
 * propiedad que importa: no puede existir un debito sin su fila, ni una fila
 * sin su debito. Y como eventoId es la clave primaria, si dos instancias
 * reciben el mismo evento a la vez, la segunda choca con la restriccion al
 * confirmar, hace rollback de su debito y el reintento la encuentra ya
 * procesada.
 *
 * POR QUE GUARDA LA RESPUESTA, Y NO SOLO EL ID
 * ============================================
 * Porque ante un duplicado no basta con ignorarlo. Si el primer intento aplico
 * la transferencia pero murio antes de publicar el resultado, ms-transferencias
 * nunca se entero y la saga quedaria colgada en PENDIENTE para siempre. El
 * duplicado es justamente la oportunidad de publicarlo: se reenvia la MISMA
 * respuesta, con el MISMO respuestaId, y si el primer envio si habia llegado,
 * el broker descarta este por duplicado. Ver PublicadorEventos.
 */
@Entity
@Table(name = "evento_procesado")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventoProcesado {

    /** El eventoId de la TransferenciaSolicitada. */
    @Id
    @Column(name = "evento_id", length = 36)
    private String eventoId;

    @Column(name = "transferencia_id", length = 36, nullable = false)
    private String transferenciaId;

    @Column(name = "aplicada", nullable = false)
    private boolean aplicada;

    @Column(name = "motivo")
    private String motivo;

    @Column(name = "saldo_origen")
    private BigDecimal saldoOrigen;

    @Column(name = "saldo_destino")
    private BigDecimal saldoDestino;

    /** eventoId del evento de respuesta, para que el reenvio sea el mismo evento. */
    @Column(name = "respuesta_id", length = 36, nullable = false)
    private String respuestaId;

    @Column(name = "procesado_por", length = 64)
    private String procesadoPor;

    @Column(name = "procesado_en", nullable = false)
    private Instant procesadoEn;
}

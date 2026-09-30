package cl.duoc.bank.transferencias.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Una fila del outbox: un evento que hay que publicar.
 *
 * EL PROBLEMA QUE RESUELVE: LA DOBLE ESCRITURA
 * ============================================
 * Aceptar una transferencia son dos escrituras en dos sistemas distintos:
 * guardarla en la base y publicar TransferenciaSolicitada en el broker. No hay
 * transaccion que abarque a los dos, asi que cualquier orden tiene un hueco:
 *
 *   base primero, broker despues: si el broker esta caido, la transferencia
 *     queda PENDIENTE para siempre y nadie se entera de que existe.
 *   broker primero, base despues: si la base falla, ms-cuentas mueve dinero
 *     de una transferencia que no quedo registrada en ninguna parte.
 *
 * El outbox cierra el hueco convirtiendo las dos escrituras en una: el evento
 * se guarda como fila EN LA MISMA TRANSACCION que la transferencia. O quedan
 * las dos o ninguna. Despues, PublicadorOutbox lee las filas sin publicar y
 * las envia; si el broker no esta, esperan en la tabla hasta que vuelva.
 *
 * El precio es que un evento puede publicarse dos veces -se envio, y el
 * servicio murio antes de marcar la fila-. Lo cubren la deteccion de
 * duplicados del broker y la idempotencia de los consumidores.
 */
@Entity
@Table(name = "evento_saliente")
@Data
@NoArgsConstructor
public class EventoSaliente {

    @Id
    @Column(name = "evento_id", length = 36)
    private String eventoId;

    @Column(nullable = false, length = 80)
    private String topico;

    /** Nombre simple del record, el mismo que viaja en la propiedad _tipo. */
    @Column(nullable = false, length = 40)
    private String tipo;

    @Column(name = "transferencia_id", nullable = false, length = 36)
    private String transferenciaId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    /** Null mientras no se haya publicado. */
    @Column(name = "publicado_en")
    private Instant publicadoEn;

    @Column(nullable = false)
    private int intentos;
}

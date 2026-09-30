package cl.duoc.bank.core.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Movimiento del historial anual, sobre la tabla del batch de la Experiencia 1.
 *
 * Alimenta el estado de cuenta que solo el canal web expone en detalle.
 */
@Entity
@Table(name = "movimiento_anual")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovimientoAnual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cuenta_id")
    private Long cuentaId;

    @Column(name = "fecha")
    private LocalDate fecha;

    /** deposito | retiro | compra */
    @Column(name = "tipo_transaccion")
    private String tipoTransaccion;

    @Column(name = "monto")
    private BigDecimal monto;

    @Column(name = "descripcion")
    private String descripcion;

    /** Marca del batch: el movimiento venia con monto cero. */
    @Column(name = "monto_cero")
    private boolean montoCero;

    @Column(name = "observacion")
    private String observacion;
}

package cl.duoc.bank.core.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Transaccion diaria, sobre la tabla que dejo el batch de la Experiencia 1.
 *
 * anomalia y motivo_anomalia los CALCULO el batch, no el BFF. Es una diferencia
 * de fondo: si cada BFF volviera a decidir que es anomalo comparando contra un
 * umbral propio, tres canales podrian discrepar sobre la misma transaccion. Aca
 * la marca se lee, y los tres coinciden porque hay una sola fuente.
 *
 * Cual de esos campos se expone si es decision de cada canal: la tabla web los
 * muestra para resaltar la fila, el movil no los envia y el cajero no expone
 * transacciones en absoluto.
 */
@Entity
@Table(name = "transaccion")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaccion {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "fecha")
    private LocalDate fecha;

    @Column(name = "monto")
    private BigDecimal monto;

    /** debito | credito. El dataset trae 'invalid' en varias filas. */
    @Column(name = "tipo")
    private String tipo;

    @Column(name = "anomalia")
    private boolean anomalia;

    @Column(name = "motivo_anomalia")
    private String motivoAnomalia;
}

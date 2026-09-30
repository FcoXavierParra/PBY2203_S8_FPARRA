package cl.duoc.bank.transferencias.dominio;

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
 * Cuanto de su cupo diario de transferencias lleva comprometido una cuenta.
 *
 * Es el recurso que la saga reserva en el paso 1 y devuelve en la
 * compensacion. Se reserva al ACEPTAR la solicitud, antes de saber si
 * ms-cuentas la aplicara, porque si se descontara recien al completarse, dos
 * solicitudes simultaneas verian las dos el cupo libre y juntas lo superarian.
 * Es el mismo razonamiento que hace que un hotel bloquee la habitacion al
 * reservar y no al llegar el huesped.
 */
@Entity
@Table(name = "cupo_diario")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CupoDiario {

    /** "cuenta:fecha", por ejemplo "105:2026-09-23". */
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false)
    private Long cuenta;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private BigDecimal reservado;

    public static String clave(Long cuenta, LocalDate fecha) {
        return cuenta + ":" + fecha;
    }
}

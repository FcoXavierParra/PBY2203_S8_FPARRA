package cl.duoc.bank.core.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Cuenta bancaria. Es el agregado principal que consumen los tres BFF.
 *
 * EL ESQUEMA ES EL QUE DEJO LA EXPERIENCIA 1
 * ------------------------------------------
 * Los nombres de columna no son libres: esta entidad se mapea sobre la tabla
 * que creo y poblo el batch de Spring Batch de las semanas 1 a 3. Por eso hay
 * saldo_inicial y saldo_final en vez de un solo "saldo", y por eso existen
 * tasa_aplicada e interes_calculado.
 *
 * Esa distincion importa para los BFF: el saldo que un cliente ve es el FINAL,
 * el que quedo despues de que el batch aplicara los intereses del mes. El
 * inicial solo le interesa al canal web, que muestra el desglose completo.
 * Servir el saldo inicial en la app o en el cajero seria mostrar una cifra
 * desactualizada con toda la apariencia de estar al dia.
 *
 * Vive en el modulo comun porque los tres canales necesitan la misma cuenta; lo
 * que cambia entre ellos es CUANTO de ella se expone.
 */
@Entity
@Table(name = "cuenta")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cuenta {

    @Id
    @Column(name = "cuenta_id")
    private Long cuentaId;

    @Column(name = "nombre")
    private String nombre;

    /** Saldo antes de aplicar intereses. Solo lo expone el canal web. */
    @Column(name = "saldo_inicial")
    private BigDecimal saldoInicial;

    @Column(name = "edad")
    private Integer edad;

    /** ahorro | prestamo. El dataset trae valores invalidos como '-1'. */
    @Column(name = "tipo")
    private String tipo;

    /** Tasa que el batch aplico segun el tipo de cuenta. */
    @Column(name = "tasa_aplicada")
    private BigDecimal tasaAplicada;

    @Column(name = "interes_calculado")
    private BigDecimal interesCalculado;

    /** El saldo vigente. Es el que ven los tres canales. */
    @Column(name = "saldo_final")
    private BigDecimal saldoFinal;

    /** Aviso de calidad que dejo el batch al procesar la fila. */
    @Column(name = "observacion")
    private String observacion;
}

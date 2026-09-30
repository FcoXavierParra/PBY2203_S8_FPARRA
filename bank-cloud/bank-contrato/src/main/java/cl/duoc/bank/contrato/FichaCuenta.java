package cl.duoc.bank.contrato;

import java.math.BigDecimal;

/**
 * Una cuenta con sus agregados ya resueltos.
 *
 * POR QUE LOS AGREGADOS VIENEN CALCULADOS Y NO EN CRUDO
 * -----------------------------------------------------
 * En la entrega anterior el BFF web pedia la cuenta y despues sus movimientos,
 * y sumaba depositos y retiros por su cuenta. Eso costaba dos llamadas EN
 * PROCESO, que no se notan.
 *
 * Al convertir el dominio en un servicio remoto esas dos llamadas pasan a ser
 * dos viajes por la red, y el listado completo -cincuenta cuentas- se
 * convertiria en ciento una peticiones HTTP. Es el problema N+1 clasico, solo
 * que con latencia de red en vez de latencia de base de datos.
 *
 * Por eso ms-cuentas agrega del lado del servidor y entrega la ficha completa:
 * una peticion por cuenta, o una sola para el listado entero. Lo que queda en
 * el BFF es la presentacion -formatear el saldo, decidir el segmento etario,
 * elegir que campos ve cada canal-, que es justamente lo suyo.
 *
 * Los campos de calculo financiero -tasa aplicada, interes, saldo final- no los
 * calcula nadie aqui: los dejo el batch de la Experiencia 1 en la base y este
 * servicio los sirve tal cual.
 */
public record FichaCuenta(
        Long cuentaId,
        String nombre,
        String tipo,
        Integer edad,
        BigDecimal saldoInicial,
        BigDecimal tasaAplicada,
        BigDecimal interesCalculado,
        BigDecimal saldoFinal,
        String observacion,

        /** Agregados sobre MOVIMIENTO_ANUAL, calculados por ms-cuentas. */
        int cantidadMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalRetiros,
        BigDecimal montoPromedio) {
}

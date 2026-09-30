package cl.duoc.bank.bff.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * La ficha de cuenta tal como la quiere un navegador: trece campos, con los
 * montos ya formateados y los agregados resueltos.
 *
 * Es el extremo pesado de la comparacion entre canales. La misma cuenta sale
 * de aqui con todo lo que una tabla web necesita para pintarse sin hacer
 * cuentas, mientras que el movil recibe cuatro campos y el cajero dos. Esa
 * diferencia es el patron BFF, y se mide en bytes en la evidencia.
 *
 * DE DONDE SALEN LOS AGREGADOS AHORA
 * ==================================
 * cantidadMovimientos, totalDepositos, totalRetiros y montoPromedio los
 * calculaba este BFF recorriendo los repositorios. Ahora los calcula
 * ms-cuentas y llegan resueltos en la FichaCuenta. Lo que sigue siendo trabajo
 * del canal es lo de presentacion: formatear el saldo en pesos, traducir la
 * edad a un segmento y redactar el aviso de calidad del dato.
 *
 * @param movimientosRecientes los ultimos movimientos DE ESTA CUENTA. Viene
 *        vacio en el listado y poblado en el detalle, y el motivo es de costo:
 *        llenarlo exige una segunda llamada HTTP por cuenta, que en un listado
 *        de cincuenta serian cincuenta viajes de red para un dato que la tabla
 *        ni siquiera muestra.
 *        <p>
 *        En la entrega anterior este campo se llenaba con las ultimas
 *        transacciones del BANCO, iguales para todas las cuentas, que es un
 *        dato de otros clientes dentro de la ficha de uno.
 */
public record CuentaWebDto(
        Long cuentaId,
        String titular,
        String tipoCuenta,
        BigDecimal saldo,
        String saldoFormateado,
        Integer edad,
        String segmentoEtario,
        int cantidadMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalRetiros,
        BigDecimal montoPromedio,
        String observacionCalidad,
        List<MovimientoAnualWebDto> movimientosRecientes) {
}

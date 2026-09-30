package cl.duoc.bank.bff.movil.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pantalla de inicio de la app: lo que se ve al abrirla.
 *
 * Cuatro campos y a lo mas cinco movimientos. La ficha web de la misma cuenta
 * tiene doce campos mas los agregados anuales; esta respuesta pesa una fraccion
 * de aquella, que es exactamente el efecto que el patron BFF busca en un cliente
 * con red variable.
 *
 * No incluye titular ni edad ni segmento: el dueno del telefono ya sabe como se
 * llama, y son datos personales que no hacen falta para pintar esta pantalla.
 * Menos datos en transito es tambien menos superficie expuesta.
 */
public record ResumenMovilDto(
        Long cuentaId,
        BigDecimal saldo,
        String moneda,
        List<MovimientoMovilDto> ultimos) {
}

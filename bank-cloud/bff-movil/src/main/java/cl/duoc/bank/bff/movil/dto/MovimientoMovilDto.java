package cl.duoc.bank.bff.movil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un movimiento visto desde el telefono: tres campos.
 *
 * El equivalente web trae siete, con monto formateado y marcas de anomalia. Aca
 * no van, y no por descuido: en una lista de movimientos de una app, la pantalla
 * muestra fecha, monto y si fue cargo o abono. Todo lo demas viajaria por la red
 * del usuario para no dibujarse nunca.
 */
public record MovimientoMovilDto(
        LocalDate fecha,
        BigDecimal monto,
        String tipo) {
}

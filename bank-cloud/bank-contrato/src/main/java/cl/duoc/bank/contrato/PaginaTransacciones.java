package cl.duoc.bank.contrato;

import java.util.List;

/**
 * Pagina de transacciones.
 *
 * El contrato lleva su propio tipo de pagina y no el Page de Spring Data a
 * proposito: Page es una interfaz de la capa de persistencia, con metodos que
 * no tienen sentido al otro lado del cable, y su serializacion JSON no es
 * estable entre versiones. Un cliente que solo habla HTTP no deberia necesitar
 * Spring Data en su classpath para entender una respuesta.
 */
public record PaginaTransacciones(
        List<TransaccionBanco> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas,
        boolean hayMas) {

    /** Lo que devuelve el fallback: una pagina vacia, no un error. */
    public static PaginaTransacciones vacia(int pagina, int tamano) {
        return new PaginaTransacciones(List.of(), pagina, tamano, 0, 0, false);
    }
}

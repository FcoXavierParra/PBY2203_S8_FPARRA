package cl.duoc.bank.bff.web.dto;

import java.util.List;

/**
 * Envoltura de paginacion.
 *
 * Existe solo en el canal web. El movil no pagina nada -devuelve un tope fijo y
 * ya- y el cajero no lista. Que este DTO viva aqui y no en el modulo comun es
 * precisamente lo que evita que la paginacion se filtre a los otros canales.
 */
public record PaginaWebDto<T>(
        List<T> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas,
        boolean haySiguiente) {
}

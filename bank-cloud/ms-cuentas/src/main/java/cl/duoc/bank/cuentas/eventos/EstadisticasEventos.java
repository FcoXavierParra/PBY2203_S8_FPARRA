package cl.duoc.bank.cuentas.eventos;

import cl.duoc.bank.core.servicio.TransferenciaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuantos eventos proceso ESTA instancia.
 *
 * Los contadores son de la instancia y no de la base a proposito: la pregunta
 * que responde la evidencia es como se repartio la carga entre las dos, y
 * eso solo lo sabe cada proceso. Se reinician al reiniciar el proceso.
 */
@Component
public class EstadisticasEventos {

    private final AtomicLong aplicadas = new AtomicLong();
    private final AtomicLong rechazadas = new AtomicLong();
    private final AtomicLong duplicados = new AtomicLong();

    void registrar(TransferenciaService.Resultado r) {
        if (r.duplicado()) {
            duplicados.incrementAndGet();
        } else if (r.aplicada()) {
            aplicadas.incrementAndGet();
        } else {
            rechazadas.incrementAndGet();
        }
    }

    @RestController
    static class Endpoint {

        private final EstadisticasEventos e;
        private final String instancia;

        Endpoint(EstadisticasEventos e, @Value("${bank.instancia}") String instancia) {
            this.e = e;
            this.instancia = instancia;
        }

        @GetMapping("/interno/eventos/estadisticas")
        public Map<String, Object> estadisticas() {
            long a = e.aplicadas.get();
            long r = e.rechazadas.get();
            long d = e.duplicados.get();
            return Map.of(
                    "instancia", instancia,
                    "procesados", a + r + d,
                    "aplicadas", a,
                    "rechazadas", r,
                    "duplicadosIgnorados", d);
        }
    }
}

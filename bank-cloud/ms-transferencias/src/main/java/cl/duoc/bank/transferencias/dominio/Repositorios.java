package cl.duoc.bank.transferencias.dominio;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Los tres repositorios del servicio, juntos porque son cortos. */
public final class Repositorios {

    private Repositorios() {
    }

    public interface Transferencias extends JpaRepository<Transferencia, String> {

        Optional<Transferencia> findByClaveIdempotencia(String clave);

        long countByEstado(String estado);
    }

    public interface Cupos extends JpaRepository<CupoDiario, String> {

        /** Bloqueado: dos solicitudes de la misma cuenta no reservan a la vez. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from CupoDiario c where c.id = :id")
        Optional<CupoDiario> bloquear(@Param("id") String id);
    }

    public interface Salientes extends JpaRepository<EventoSaliente, String> {

        /** Los pendientes, en el orden en que se generaron. */
        List<EventoSaliente> findByPublicadoEnIsNullOrderByCreadoEnAsc(Limit limite);

        long countByPublicadoEnIsNull();

        long countByPublicadoEnIsNotNull();
    }
}

package cl.duoc.bank.core.repositorio;

import cl.duoc.bank.core.dominio.Transaccion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /** Para el canal web: recorrido completo y paginado. */
    Page<Transaccion> findAllByFechaBetweenOrderByFechaDesc(LocalDate desde, LocalDate hasta, Pageable pagina);

    /**
     * Para el canal movil: las ultimas N y nada mas.
     *
     * Es una consulta distinta y no la misma con otro Pageable a proposito. El
     * movil nunca debe poder pedir "todas": si el limite fuera un parametro del
     * cliente, el ahorro de ancho de banda dependeria de que el frontend se
     * porte bien, y el patron BFF existe justamente para no depender de eso.
     */
    List<Transaccion> findTop5ByOrderByFechaDesc();
}

package cl.duoc.bank.core.repositorio;

import cl.duoc.bank.core.dominio.MovimientoAnual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovimientoAnualRepository extends JpaRepository<MovimientoAnual, Long> {

    List<MovimientoAnual> findByCuentaIdOrderByFechaAsc(Long cuentaId);
}

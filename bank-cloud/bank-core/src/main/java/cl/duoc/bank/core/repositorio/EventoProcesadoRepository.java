package cl.duoc.bank.core.repositorio;

import cl.duoc.bank.core.dominio.EventoProcesado;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoProcesadoRepository extends JpaRepository<EventoProcesado, String> {
}

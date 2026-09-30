package cl.duoc.bank.core.servicio;

import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.MovimientoAnual;
import cl.duoc.bank.core.dominio.Transaccion;
import cl.duoc.bank.core.repositorio.CuentaRepository;
import cl.duoc.bank.core.repositorio.MovimientoAnualRepository;
import cl.duoc.bank.core.repositorio.TransaccionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Consultas de dominio, sin forma de canal.
 *
 * Este servicio devuelve ENTIDADES, nunca DTO. Es la frontera que sostiene el
 * patron: si aca apareciera un metodo como obtenerResumenMovil(), la logica de
 * un canal se habria filtrado al backend comun y los BFF pasarian a ser
 * cascarones que solo reenvian. Cada BFF toma estas entidades y decide que
 * parte de ellas expone y con que forma.
 */
@Service
@RequiredArgsConstructor
public class ConsultaService {

    private final CuentaRepository cuentaRepository;
    private final TransaccionRepository transaccionRepository;
    private final MovimientoAnualRepository movimientoAnualRepository;

    public Optional<Cuenta> buscarCuenta(Long cuentaId) {
        return cuentaRepository.findById(cuentaId);
    }

    public List<Cuenta> listarCuentas() {
        return cuentaRepository.findAll();
    }

    /** Recorrido paginado por rango de fechas. Lo usa el canal web. */
    public Page<Transaccion> transaccionesPaginadas(LocalDate desde, LocalDate hasta, int pagina, int tamano) {
        return transaccionRepository.findAllByFechaBetweenOrderByFechaDesc(
                desde, hasta, PageRequest.of(pagina, tamano));
    }

    /** Las ultimas cinco, sin parametros. Lo usa el canal movil. */
    public List<Transaccion> ultimasTransacciones() {
        return transaccionRepository.findTop5ByOrderByFechaDesc();
    }

    public List<MovimientoAnual> movimientosDe(Long cuentaId) {
        return movimientoAnualRepository.findByCuentaIdOrderByFechaAsc(cuentaId);
    }

    /**
     * Todos los movimientos, de todas las cuentas.
     *
     * Existe para que el listado completo de cuentas los pida UNA vez y los
     * agrupe, en lugar de hacer una consulta por cuenta. Ver el javadoc de
     * CuentaInternaController.listarCuentas.
     */
    public List<MovimientoAnual> todosLosMovimientos() {
        return movimientoAnualRepository.findAll();
    }

    public long totalTransacciones() {
        return transaccionRepository.count();
    }

    public long totalCuentas() {
        return cuentaRepository.count();
    }
}

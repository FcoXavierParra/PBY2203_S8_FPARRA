package cl.duoc.bank.core.repositorio;

import cl.duoc.bank.core.dominio.Cuenta;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CuentaRepository extends JpaRepository<Cuenta, Long> {

    /**
     * Lee la cuenta con SELECT ... FOR UPDATE: nadie mas la puede modificar
     * hasta que esta transaccion termine.
     *
     * POR QUE HACE FALTA AHORA
     * ------------------------
     * En la semana 6 habia un unico ms-cuentas y el javadoc de
     * OperacionService.retirar decia que @Transactional bastaba para que dos
     * cajeros no autorizaran dos retiros que juntos superan el saldo. No era
     * cierto: con el aislamiento por defecto -READ COMMITTED- dos
     * transacciones pueden leer el mismo saldo, restar cada una su monto y
     * escribir; la segunda pisa a la primera y un retiro desaparece. Que no se
     * viera era cuestion de que las peticiones llegaban de a una.
     *
     * Esta semana ms-cuentas corre en DOS instancias que consumen la misma
     * suscripcion, y una rafaga de transferencias hace que eso deje de ser
     * teorico. El bloqueo convierte el leer-restar-escribir en una operacion
     * que las demas tienen que esperar. La evidencia lo comprueba sumando los
     * saldos de todas las cuentas antes y despues de la rafaga: una
     * transferencia mueve dinero, no lo crea ni lo destruye.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cuenta c where c.cuentaId = :id")
    Optional<Cuenta> bloquear(@Param("id") Long id);
}

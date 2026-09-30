package cl.duoc.bank.core.servicio;

import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.repositorio.CuentaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Operaciones que cambian el estado de una cuenta.
 *
 * DONDE TERMINA EL DOMINIO Y DONDE EMPIEZA EL CANAL
 * ------------------------------------------------
 * Esta es la frontera mas facil de cruzar sin darse cuenta, asi que conviene
 * dejarla escrita:
 *
 *   Dominio (aqui)  : que un retiro no deje la cuenta en negativo, que el monto
 *                     sea positivo, y que el debito ocurra de forma atomica. Son
 *                     verdades del banco, iguales para quien sea que las invoque.
 *
 *   Canal (el BFF)  : que un cajero solo entregue multiplos de 10.000, que tope
 *                     en 200.000 por operacion, o que la app movil no permita
 *                     retirar en absoluto. Son politicas del punto de atencion y
 *                     cambian de uno a otro.
 *
 * Si el limite del cajero viviera aca, cualquier canal futuro lo heredaria sin
 * pedirlo. Si la validacion de saldo viviera en el BFF, habria que repetirla en
 * cada canal que agregue operaciones y bastaria con olvidarla una vez para
 * permitir un descubierto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperacionService {

    private final CuentaRepository cuentaRepository;

    /** Resultado de un intento de retiro. */
    public record Retiro(boolean autorizado, String motivo, BigDecimal saldoResultante) {

        public static Retiro rechazado(String motivo, BigDecimal saldo) {
            return new Retiro(false, motivo, saldo);
        }
    }

    /**
     * Debita un monto de la cuenta si el saldo alcanza.
     *
     * La lectura del saldo y la escritura del nuevo valor tienen que ser
     * indivisibles: dos cajeros atendiendo la misma cuenta a la vez no pueden
     * leer ambos el saldo original y autorizar dos retiros que juntos lo
     * superan.
     *
     * CORRECCION RESPECTO DE LA SEMANA 6: la version anterior leia con
     * findById y confiaba en que @Transactional bastaba. No basta -ver
     * CuentaRepository.bloquear-. Ahora la fila se lee bloqueada.
     */
    @Transactional
    public Retiro retirar(Long cuentaId, BigDecimal monto) {

        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            return Retiro.rechazado("El monto debe ser mayor que cero", null);
        }

        Optional<Cuenta> encontrada = cuentaRepository.bloquear(cuentaId);
        if (encontrada.isEmpty()) {
            return Retiro.rechazado("Cuenta inexistente", null);
        }

        Cuenta cuenta = encontrada.get();
        BigDecimal saldo = cuenta.getSaldoFinal();

        if (saldo == null) {
            return Retiro.rechazado("La cuenta no tiene saldo registrado", null);
        }
        if (saldo.compareTo(monto) < 0) {
            return Retiro.rechazado("Saldo insuficiente", saldo);
        }

        cuenta.setSaldoFinal(saldo.subtract(monto));
        cuentaRepository.save(cuenta);

        log.info("Retiro autorizado en cuenta {}: {} -> saldo {}", cuentaId, monto, cuenta.getSaldoFinal());
        return new Retiro(true, "Retiro autorizado", cuenta.getSaldoFinal());
    }
}

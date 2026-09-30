package cl.duoc.bank.cliente;

/**
 * ms-transferencias no respondio, o su circuito esta abierto. Mismo papel que
 * CuentasNoDisponible, para el otro servicio: el BFF decide que responder.
 */
public class TransferenciasNoDisponible extends RuntimeException {

    private final boolean circuitoAbierto;

    public TransferenciasNoDisponible(String operacion, Throwable causa, boolean circuitoAbierto) {
        super("ms-transferencias no disponible en la operacion '" + operacion + "': "
                + (circuitoAbierto ? "circuito abierto" : causa.getClass().getSimpleName()), causa);
        this.circuitoAbierto = circuitoAbierto;
    }

    public boolean isCircuitoAbierto() {
        return circuitoAbierto;
    }
}

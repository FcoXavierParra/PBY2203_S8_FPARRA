package cl.duoc.bank.cliente;

/**
 * ms-cuentas no contesto, o el circuito esta abierto y ni se intento.
 *
 * POR QUE EL FALLBACK LANZA UNA EXCEPCION EN VEZ DE DEVOLVER UN VALOR
 * -------------------------------------------------------------------
 * Porque no existe una respuesta alternativa que sirva a los tres canales, y
 * elegir una aqui obligaria a los otros dos a conformarse.
 *
 * Un navegador puede mostrar la ficha con los agregados en blanco y un aviso
 * de que los datos estan incompletos; eso es preferible a una pantalla de
 * error. Un cajero automatico NO puede hacer nada parecido: si no sabe el
 * saldo, lo unico honesto es declararse fuera de servicio, porque una pantalla
 * con un saldo dudoso frente a alguien que va a retirar dinero es peor que una
 * que dice que vuelva mas tarde.
 *
 * Asi que el cliente informa el hecho -el Backend no esta disponible- y cada
 * BFF decide que responde. La degradacion es una decision de canal, igual que
 * el recorte de campos, y por eso vive donde viven las decisiones de canal.
 *
 * Esta clase lleva ademas si el circuito estaba ABIERTO, que es informacion
 * distinta: "lo intente y fallo" no es lo mismo que "ni lo intente porque
 * viene fallando", y para quien lee la evidencia la diferencia es justamente
 * lo que demuestra que el Circuit Breaker esta operando.
 */
public class CuentasNoDisponible extends RuntimeException {

    private final boolean circuitoAbierto;

    public CuentasNoDisponible(String operacion, Throwable causa, boolean circuitoAbierto) {
        super("ms-cuentas no disponible en la operacion '" + operacion + "': "
                + (circuitoAbierto ? "circuito abierto" : causa.getClass().getSimpleName()), causa);
        this.circuitoAbierto = circuitoAbierto;
    }

    public boolean isCircuitoAbierto() {
        return circuitoAbierto;
    }
}

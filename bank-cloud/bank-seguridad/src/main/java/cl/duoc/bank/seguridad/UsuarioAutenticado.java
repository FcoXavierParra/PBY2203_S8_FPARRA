package cl.duoc.bank.seguridad;

/**
 * Quien viene en el token, una vez validado.
 *
 * QUE PROBLEMA RESUELVE, Y POR QUE NO EXISTIA ANTES
 * =================================================
 * En la entrega anterior el principal autenticado era una cadena: el nombre de
 * usuario en el canal web, el identificador del aparato en el movil, el numero
 * de cuenta en el cajero. Tres canales, tres significados, y ninguna forma de
 * preguntar de manera uniforme "que cuenta puede ver este". El resultado fue
 * que dos de los tres canales no lo preguntaron nunca y tomaron el numero de
 * cuenta del path de la peticion, es decir, del propio cliente.
 *
 * Este tipo hace que la cuenta sea parte de la identidad autenticada y no un
 * parametro que el cliente propone. Viaja firmada dentro del JWT, asi que
 * nadie puede cambiarla sin invalidar la firma.
 *
 * POR QUE cuentaId PUEDE SER null
 * ===============================
 * Porque hay identidades legitimas que no son titulares de una cuenta. El
 * ejecutivo del canal web es la que existe hoy: su atribucion es recorrer la
 * cartera completa, y forzarlo a tener una cuenta asignada para poder
 * autenticarse seria inventar un dato. Su acceso se decide por rol; el del
 * cliente, por pertenencia. Ver AutorizacionCuenta.
 */
public record UsuarioAutenticado(String sujeto, String canal, Long cuentaId) {

    /**
     * Spring Security usa el toString del principal para Authentication.getName(),
     * que es lo que termina en los registros de auditoria y en los mensajes de
     * log. Sin esto se imprimiria el record completo, con la cuenta incluida.
     */
    @Override
    public String toString() {
        return sujeto;
    }
}

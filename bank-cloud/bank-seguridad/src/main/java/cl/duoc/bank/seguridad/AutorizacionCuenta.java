package cl.duoc.bank.seguridad;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * La pregunta que la entrega anterior no hizo: quien pregunta, puede ver ESTA
 * cuenta.
 *
 * EL FALLO QUE ESTA CLASE CIERRA
 * ==============================
 * La evaluacion de la semana 5 dejo el criterio de configuracion segura en
 * logro insuficiente, y revisando el codigo la causa es una sola: habia
 * autenticacion en los tres canales y no habia autorizacion a nivel de objeto
 * en dos de ellos.
 *
 * Concretamente, GET /api/web/cuentas/{cuentaId} y
 * GET /api/movil/cuentas/{cuentaId}/resumen tomaban el numero de cuenta del
 * path y consultaban, sin cruzarlo con la identidad del que preguntaba. El
 * usuario 'cliente', debidamente autenticado con su clave, podia leer la ficha
 * completa -titular, saldo, edad, movimientos- de cualquier cliente del banco
 * cambiando un numero en la URL. Es la primera categoria del OWASP API Top 10,
 * y no la detecta ninguna prueba que solo compruebe que el endpoint pide
 * credenciales.
 *
 * El canal cajero era el unico que lo hacia bien: tomaba la cuenta del token y
 * nunca de la peticion. El patron correcto ya estaba en el proyecto; lo que
 * faltaba era aplicarlo en los otros dos, y eso es lo que hace esta clase.
 *
 * POR QUE UNA CLASE COMPARTIDA Y NO UN if EN CADA CONTROLADOR
 * ===========================================================
 * Porque la version con un if en cada controlador es exactamente la que
 * fallo. La regla tiene que estar en un solo lugar, con un nombre que se pueda
 * buscar, para que agregar un endpoint nuevo que reciba un cuentaId y no
 * llamar a esto sea una omision visible.
 */
public final class AutorizacionCuenta {

    private AutorizacionCuenta() {
    }

    /**
     * La cuenta del token, o null si la identidad no es titular de una.
     *
     * SEMANA 8: dos formas de identidad, y en las dos la cuenta viene firmada.
     *   - Jwt: el token del servidor de autorizacion (canales web y movil). La
     *     cuenta es el claim 'cuenta', que solo el auth-server puede escribir.
     *   - UsuarioAutenticado: la sesion de tarjeta del cajero, que el propio BFF
     *     abre tras validar el PIN y guarda de su lado.
     */
    public static Long cuentaDe(Authentication autenticacion) {
        if (autenticacion == null) {
            return null;
        }
        if (autenticacion.getPrincipal() instanceof Jwt jwt) {
            // Number y no Long: el JSON no distingue enteros por tamanio, y 105
            // llega como Integer. Un cast directo a Long revienta.
            Object cuenta = jwt.getClaim(ConversorJwt.CLAIM_CUENTA);
            return cuenta instanceof Number n ? n.longValue() : null;
        }
        if (autenticacion.getPrincipal() instanceof UsuarioAutenticado u) {
            return u.cuentaId();
        }
        return null;
    }

    /**
     * Si la identidad autenticada puede operar sobre la cuenta pedida.
     *
     * Dos caminos, y ninguno consulta la peticion:
     *
     *   1. PERTENENCIA: la cuenta del token coincide con la pedida. Es el caso
     *      del titular, y es el que cubre a los clientes de los tres canales.
     *   2. ATRIBUCION: la identidad tiene un rol que le permite ver cuentas
     *      ajenas. Hoy solo el ejecutivo del canal web.
     *
     * El orden importa poco, pero la ausencia de un tercer camino importa
     * mucho: no hay ninguna rama que acepte el cuentaId porque venga bien
     * formado o porque la cuenta exista.
     */
    public static boolean puedeAcceder(Authentication autenticacion, Long cuentaPedida, String rolAmplio) {
        if (autenticacion == null || cuentaPedida == null) {
            return false;
        }
        if (cuentaPedida.equals(cuentaDe(autenticacion))) {
            return true;
        }
        return rolAmplio != null && tieneRol(autenticacion, rolAmplio);
    }

    /** Variante sin rol amplio: solo el titular. Es lo que usan movil y cajero. */
    public static boolean puedeAcceder(Authentication autenticacion, Long cuentaPedida) {
        return puedeAcceder(autenticacion, cuentaPedida, null);
    }

    private static boolean tieneRol(Authentication autenticacion, String rol) {
        String conPrefijo = rol.startsWith("ROLE_") ? rol : "ROLE_" + rol;
        for (GrantedAuthority a : autenticacion.getAuthorities()) {
            if (conPrefijo.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}

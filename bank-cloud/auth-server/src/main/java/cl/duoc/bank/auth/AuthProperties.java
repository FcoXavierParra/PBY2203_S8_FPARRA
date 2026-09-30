package cl.duoc.bank.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clientes registrados, usuarios y reglas de audiencia del servidor de
 * autorizacion. Llegan del Config Server (config-repo/auth-server.yml), con las
 * claves y secretos en variables de entorno.
 *
 * @param emisor    valor del claim 'iss'. Se fija aqui y no se deduce de la
 *                  peticion: dentro de Docker los servicios llegan a este
 *                  servidor como http://auth-server:9000 y desde afuera como
 *                  http://IP-PUBLICA:9000. Si el emisor dependiera de por
 *                  donde entro la peticion, un token pedido desde afuera
 *                  llevaria un 'iss' que los servidores de recursos no
 *                  reconocerian.
 * @param recursos  prefijo de scope -> servidor de recursos (su audiencia).
 *                  'cuentas.leer' da un token con aud = ms-cuentas.
 */
@ConfigurationProperties(prefix = "bank.auth")
public record AuthProperties(String emisor,
                             Map<String, String> recursos,
                             List<Cliente> clientes,
                             List<Usuario> usuarios) {

    public AuthProperties {
        if (emisor == null || emisor.isBlank()) {
            throw new IllegalStateException("Falta bank.auth.emisor");
        }
        recursos = recursos == null ? Map.of() : recursos;
        clientes = clientes == null ? List.of() : clientes;
        usuarios = usuarios == null ? List.of() : usuarios;
    }

    /**
     * Dos clases de cliente, y la diferencia es de seguridad, no de comodidad.
     *
     * PUBLICO   una aplicacion que corre en manos del usuario -navegador,
     *           telefono- y por lo tanto NO puede guardar un secreto: cualquiera
     *           puede descompilarla. Usa authorization_code con PKCE, donde la
     *           prueba de posesion es un codigo de un solo uso generado en cada
     *           login, no un secreto fijo.
     * MAQUINA   un proceso del banco -un BFF, un cajero automatico- que si
     *           guarda su secreto y actua en nombre propio, sin una persona
     *           detras. Usa client_credentials.
     */
    public enum Tipo { PUBLICO, MAQUINA }

    /**
     * @param canal      solo PUBLICO: canal que se escribe en el token (claim 'canal').
     * @param audiencia  solo PUBLICO: el BFF que acepta estos tokens. En un
     *                   MAQUINA la audiencia se deduce de los scopes.
     */
    public record Cliente(String id,
                          Tipo tipo,
                          String secreto,
                          String canal,
                          String audiencia,
                          List<String> redirecciones,
                          List<String> scopes,
                          Duration duracionToken) {

        public Cliente {
            redirecciones = redirecciones == null ? List.of() : redirecciones;
            scopes = scopes == null ? List.of() : scopes;
            duracionToken = duracionToken == null ? Duration.ofMinutes(5) : duracionToken;
        }
    }

    /**
     * @param cuenta   cuenta de la que la persona es titular; viaja firmada en el
     *                 claim 'cuenta' y es contra lo que los BFF comparan la cuenta
     *                 pedida (AutorizacionCuenta). Nula para quien no es titular.
     * @param canales  canales por los que puede entrar. Un ejecutivo trabaja desde
     *                 la web del banco, no desde la aplicacion movil de clientes.
     */
    public record Usuario(String usuario,
                          String clave,
                          Long cuenta,
                          List<String> roles,
                          List<String> canales) {

        public Usuario {
            roles = roles == null ? List.of() : roles;
            canales = canales == null ? List.of() : canales;
        }
    }

    public Optional<Cliente> cliente(String id) {
        return clientes.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<Usuario> usuario(String nombre) {
        return usuarios.stream().filter(u -> u.usuario().equals(nombre)).findFirst();
    }

    /** Servidor de recursos al que pertenece un scope, por su prefijo. */
    public Optional<String> recursoDe(String scope) {
        int punto = scope.indexOf('.');
        return punto < 0 ? Optional.empty() : Optional.ofNullable(recursos.get(scope.substring(0, punto)));
    }
}

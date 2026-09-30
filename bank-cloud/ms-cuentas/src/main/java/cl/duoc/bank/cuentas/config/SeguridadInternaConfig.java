package cl.duoc.bank.cuentas.config;

import cl.duoc.bank.seguridad.ConversorJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de ms-cuentas: aqui se autentican SERVICIOS, no personas.
 *
 * SEMANA 8: DE BASIC A OAUTH 2.0 CON SCOPES
 * -----------------------------------------
 * Hasta la semana 7 los BFF se identificaban con un usuario y clave fijos
 * (svc-consulta, svc-operacion). Tenia tres problemas: la clave no caducaba
 * nunca, viajaba en cada peticion, y este servicio tenia que guardar la lista
 * de quien es quien. Ahora ms-cuentas es un servidor de recursos OAuth 2.0:
 *   - cada BFF pide al auth-server un token con client_credentials y lo usa
 *     unos minutos (bank-cliente lo renueva solo);
 *   - el token dice QUE puede hacer su portador (scopes), no quien es: aqui no
 *     queda ninguna lista de usuarios ni ninguna clave;
 *   - solo se acepta un token firmado por el auth-server y dirigido a este
 *     servicio (aud = ms-cuentas). Uno pedido para ms-transferencias, o el
 *     token de una persona para su BFF, se rechaza con 401 aunque su firma sea
 *     valida. Emisor y audiencia estan en config-repo (application.yml y
 *     ms-cuentas.yml).
 *
 * La identidad del CLIENTE FINAL sigue sin llegar hasta aqui, y es deliberado.
 * Este servicio no decide si Juan puede ver la cuenta 42: eso lo decide el BFF,
 * que es quien recibio el token de Juan y sabe que cuenta le corresponde. Ver
 * AutorizacionCuenta en bank-seguridad.
 *
 * POR QUE ESTE SERVICIO NECESITA AUTENTICACION SI ES INTERNO
 * ----------------------------------------------------------
 * Porque "interno" describe la intencion, no una garantia. Este proceso tiene
 * el unico acceso a los saldos del banco y expone la operacion de retiro. En
 * Docker su puerto no se publica, pero cualquier otro contenedor de la red
 * puede alcanzarlo: si uno quedara comprometido, sin autenticacion aqui podria
 * leer y operar sobre todas las cuentas.
 */
@Configuration
public class SeguridadInternaConfig {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                // Sin CSRF y sin sesion: el cliente es un proceso que manda su
                // token en cada peticion. No hay navegador ni cookie que un
                // tercero pueda hacer viajar sin querer.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Eureka y el healthcheck de Docker consultan la salud sin
                        // credenciales.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Leer: los tres BFF y la consola de operaciones.
                        .requestMatchers(HttpMethod.GET, "/interno/**").hasAuthority("SCOPE_cuentas.leer")
                        // Mover dinero: solo el BFF del cajero tiene este scope. El
                        // movil y la web pueden LEER cuentas, pero un token suyo
                        // no retira aunque alguien lo robe.
                        .requestMatchers(HttpMethod.POST, "/interno/cuentas/*/retiro").hasAuthority("SCOPE_cuentas.operar")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                .build();
    }
}

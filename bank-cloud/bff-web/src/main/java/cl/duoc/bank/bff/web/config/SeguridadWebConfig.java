package cl.duoc.bank.bff.web.config;

import cl.duoc.bank.seguridad.ConversorJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del canal WEB: servidor de recursos OAuth 2.0.
 *
 * SEMANA 8: EL LOGIN SALIO DE AQUI
 * ================================
 * Hasta la semana 7 este BFF recibia usuario y clave en POST /api/web/login,
 * los comprobaba contra su propia lista y firmaba un JWT con su secreto HMAC.
 * Ahora la persona se autentica en el auth-server (authorization_code con
 * PKCE) y llega aqui con un token que este BFF no emitio y no podria emitir:
 *   - la firma se verifica con la clave PUBLICA del auth-server;
 *   - el token tiene que ir dirigido a este canal (aud = bff-web, ver
 *     config-repo/bff-web.yml). El token que la misma persona obtuvo para la
 *     aplicacion movil se rechaza aqui con 401, aunque su firma sea perfecta:
 *     es la separacion por canal de las semanas anteriores, ahora expresada
 *     como audiencia en vez de como una clave distinta por canal.
 * Este proceso ya no ve contrasenas, ni guarda usuarios, ni tiene nada con
 * que firmar.
 *
 * POR QUE ESTE CANAL TIENE ROLES
 * ==============================
 * Aca quien se autentica es una persona, y tiene sentido distinguir que puede
 * ver cada quien: un cliente entra a su ficha, un ejecutivo recorre ademas la
 * cartera completa. Los roles viajan firmados en el claim 'roles'.
 *
 * /api/web/transacciones exige rol EJECUTIVO (desde la semana 6): devuelve el
 * libro de transacciones del banco entero, y la tabla no tiene columna de
 * cuenta, asi que no se puede acotar al titular que pregunta.
 *
 * La autorizacion por cuenta -que un cliente solo vea la suya- no se puede
 * expresar aqui, porque depende del numero que trae cada peticion. Eso lo
 * resuelve el controlador con AutorizacionCuenta, contra el claim 'cuenta'.
 */
@Configuration
public class SeguridadWebConfig {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                // Sin CSRF: el token viaja en la cabecera Authorization, que un
                // sitio ajeno no puede hacer que el navegador agregue solo. El
                // CSRF protege cookies, y aqui no hay ninguna.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("EJECUTIVO")
                        .requestMatchers(HttpMethod.GET, "/api/web/cuentas").hasRole("EJECUTIVO")
                        .requestMatchers(HttpMethod.GET, "/api/web/transacciones").hasRole("EJECUTIVO")
                        .requestMatchers("/api/web/**").hasRole("CLIENTE")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                .build();
    }
}

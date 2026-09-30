package cl.duoc.bank.bff.movil.config;

import cl.duoc.bank.seguridad.ConversorJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del canal MOVIL: servidor de recursos OAuth 2.0.
 *
 * SEMANA 8: QUIEN ES EL SUJETO DEL TOKEN
 * --------------------------------------
 * Hasta la semana 7 la aplicacion se presentaba con un token de dispositivo
 * compartido (X-Device-Token) y un deviceId, y este BFF firmaba un JWT cuyo
 * sujeto era ese deviceId: un valor que elegia el propio cliente. Ahora la
 * aplicacion movil es un cliente OAuth 2.0 publico (canal-movil) y la PERSONA
 * se autentica en el auth-server con authorization_code + PKCE, el flujo que
 * el estandar indica para aplicaciones nativas (RFC 8252). El sujeto del token
 * es la persona autenticada y su cuenta viaja firmada; nada de eso lo decide
 * la aplicacion.
 *
 * El token tiene que ir dirigido a este canal (aud = bff-movil): el de la web
 * se rechaza aqui con 401. Y el auth-server solo lo emite a quien esta
 * habilitado para el canal movil: el ejecutivo, que trabaja desde la web del
 * banco, recibe access_denied.
 *
 * POR QUE 15 MINUTOS Y NO 30 COMO EL CANAL WEB
 * --------------------------------------------
 * Un telefono se pierde y se roba con una facilidad que un computador de
 * escritorio no tiene. La ventana de exposicion de un token robado tiene que
 * ser mas corta. La duracion la fija el auth-server por cliente
 * (config-repo/auth-server.yml).
 */
@Configuration
public class SeguridadMovilConfig {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/movil/**").hasRole("CLIENTE")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                .build();
    }
}

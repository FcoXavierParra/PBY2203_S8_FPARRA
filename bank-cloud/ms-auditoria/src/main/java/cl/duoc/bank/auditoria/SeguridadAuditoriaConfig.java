package cl.duoc.bank.auditoria;

import cl.duoc.bank.seguridad.ConversorJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * El registro de auditoria es de SOLO LECTURA tambien por HTTP: se permiten
 * GET y nada mas. Los eventos entran unicamente por el broker; no existe un
 * endpoint para agregar, corregir ni borrar una entrada, y eso es lo que le da
 * valor como registro.
 *
 * SEMANA 8: servidor de recursos OAuth 2.0 (ver SeguridadInternaConfig de
 * ms-cuentas). Leer exige el scope auditoria.leer, que hoy solo tiene la consola
 * de operaciones: ningun canal de clientes consulta la auditoria.
 */
@Configuration
public class SeguridadAuditoriaConfig {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/interno/auditoria/**").hasAuthority("SCOPE_auditoria.leer")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                .build();
    }
}

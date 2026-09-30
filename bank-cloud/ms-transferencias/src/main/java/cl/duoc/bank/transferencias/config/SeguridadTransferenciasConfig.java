package cl.duoc.bank.transferencias.config;

import cl.duoc.bank.seguridad.ConversorJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Servidor de recursos OAuth 2.0, mismo esquema que ms-cuentas: ver el javadoc
 * de SeguridadInternaConfig alli.
 *
 * SEMANA 8: la credencial unica svc-transferencias (rol TRANSFERIR, que abria
 * todo /interno/**) se reemplaza por tres scopes, uno por lo que realmente se
 * hace:
 *   transferencias.crear   iniciar una transferencia. Solo bff-web.
 *   transferencias.leer    consultar el estado de una. bff-web, para su titular.
 *   transferencias.operar  mirar por dentro -outbox, cupos, circuitos- y cargar
 *                          lotes de prueba. Solo la consola de operaciones.
 * Antes, el mismo usuario que el canal web usaba para transferir podia vaciar
 * el outbox o cargar un lote de cuarenta transferencias.
 *
 * El orden de las reglas importa: /outbox y /lote se declaran antes que las
 * generales porque tambien calzan con /{id} y con el POST de crear.
 */
@Configuration
public class SeguridadTransferenciasConfig {

    private static final String BASE = "/interno/transferencias";

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_transferencias.operar")
                        .requestMatchers(HttpMethod.POST, BASE + "/lote").hasAuthority("SCOPE_transferencias.operar")
                        .requestMatchers(HttpMethod.GET, BASE + "/outbox").hasAuthority("SCOPE_transferencias.operar")
                        .requestMatchers(HttpMethod.POST, BASE).hasAuthority("SCOPE_transferencias.crear")
                        .requestMatchers(HttpMethod.GET, BASE + "/**")
                                .hasAnyAuthority("SCOPE_transferencias.leer", "SCOPE_transferencias.operar")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                .build();
    }
}

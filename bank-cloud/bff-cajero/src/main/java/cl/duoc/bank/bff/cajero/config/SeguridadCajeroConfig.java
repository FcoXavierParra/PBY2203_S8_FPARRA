package cl.duoc.bank.bff.cajero.config;

import cl.duoc.bank.seguridad.ConversorJwt;
import cl.duoc.bank.seguridad.UsuarioAutenticado;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Seguridad del canal CAJERO: dos credenciales, una por cada actor.
 *
 * 1. LA MAQUINA: token OAuth 2.0 (client_credentials)
 * ----------------------------------------------------
 * El cajero automatico es un cliente registrado en el auth-server
 * (cajero-terminal-01) y se presenta con un token de scope cajero.terminal,
 * dirigido a este BFF (aud = bff-cajero). Reemplaza a la clave de terminal
 * compartida de las semanas anteriores (X-ATM-Terminal): esa clave era la misma
 * en todos los cajeros, no vencia nunca, y si se filtraba habia que cambiarla
 * en todas las maquinas a la vez. Ahora cada terminal tiene su propia identidad
 * y un token que dura cinco minutos.
 *
 * Por que client_credentials y no authorization_code como web y movil: un
 * cajero no tiene navegador ni una persona que inicie sesion en el auth-server.
 * Es una maquina que actua en nombre propio, que es exactamente el caso de
 * client_credentials.
 *
 * 2. LA PERSONA: sesion de tarjeta (opaca, atada al terminal)
 * ------------------------------------------------------------
 * La persona se identifica frente a la maquina con tarjeta y PIN, y eso lo
 * valida este BFF: POST /api/cajero/sesion abre una sesion de dos minutos y
 * devuelve un identificador opaco, que el cajero manda despues en
 * X-Sesion-Cajero junto con SU token. La sesion solo vale desde el terminal
 * que la abrio y se cierra sola al completar el retiro. Ver SesionCajeroService.
 *
 * Una peticion sin token de terminal responde 401; con token de terminal pero
 * sin sesion vigente, 403: la maquina es legitima, pero no hay nadie con una
 * tarjeta frente a ella.
 */
@Configuration
public class SeguridadCajeroConfig {

    /** La cabecera con la que el cajero presenta la sesion de tarjeta. */
    public static final String CABECERA_SESION = "X-Sesion-Cajero";

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http, SesionCajeroService sesiones) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/api/cajero/sesion").hasAuthority("SCOPE_cajero.terminal")
                        .requestMatchers("/api/cajero/**").hasRole("SESION")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ConversorJwt())))
                // Despues de validar el token de la maquina: la sesion de tarjeta
                // solo se considera si ya hay un terminal autenticado.
                .addFilterAfter(new FiltroSesion(sesiones), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * Si el terminal autenticado presenta una sesion vigente abierta por el
     * mismo, la identidad pasa a ser la de la persona de esa tarjeta, con el rol
     * SESION. Si no, la peticion sigue como la de un terminal sin sesion.
     */
    @RequiredArgsConstructor
    public static class FiltroSesion extends OncePerRequestFilter {

        private static final SimpleGrantedAuthority TERMINAL = new SimpleGrantedAuthority("SCOPE_cajero.terminal");

        private final SesionCajeroService sesiones;

        @Override
        protected void doFilterInternal(HttpServletRequest peticion,
                                        HttpServletResponse respuesta,
                                        FilterChain cadena) throws ServletException, IOException {
            var contexto = SecurityContextHolder.getContext();
            if (contexto.getAuthentication() instanceof JwtAuthenticationToken terminal
                    && terminal.getAuthorities().contains(TERMINAL)) {
                String id = peticion.getHeader(CABECERA_SESION);
                sesiones.vigente(id, terminal.getName()).ifPresent(s -> contexto.setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                // La cuenta sale de la sesion guardada de este lado,
                                // nunca de la peticion. Ver AutorizacionCuenta.
                                new UsuarioAutenticado(String.valueOf(s.cuentaId()), "cajero", s.cuentaId()),
                                // La credencial es el identificador de la sesion:
                                // el controlador lo necesita para cerrarla tras el retiro.
                                id,
                                List.of(TERMINAL, new SimpleGrantedAuthority("ROLE_SESION")))));
            }
            cadena.doFilter(peticion, respuesta);
        }
    }
}

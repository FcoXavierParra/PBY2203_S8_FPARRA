package cl.duoc.bank.broker;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de la consola HTTP del broker, y su indicador de salud.
 *
 * La consola puede publicar mensajes en cualquier topico, asi que abrirla sin
 * credenciales seria dejar una puerta para inyectar eventos falsos en la saga
 * -exactamente lo que la matriz de permisos de BrokerConfig impide por JMS-.
 * Se entra con el MISMO usuario administrador del broker: una sola credencial
 * que rotar, en el Config Server.
 */
@Configuration
public class SeguridadConsolaConfig {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(b -> {})
                .build();
    }

    @Bean
    public UserDetailsService administrador(BrokerProperties props, PasswordEncoder encoder) {
        BrokerProperties.Usuario admin = props.usuarios().stream()
                .filter(u -> BrokerConfig.ROL_ADMIN.equals(u.rol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El Config Server no entrego un usuario con rol " + BrokerConfig.ROL_ADMIN));
        return new InMemoryUserDetailsManager(User.withUsername(admin.usuario())
                .password(encoder.encode(admin.clave()))
                .roles("ADMIN")
                .build());
    }

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * UP solo si el servidor Artemis esta activo, no solo el proceso Java.
     * levantar.ps1 encadena el arranque por este endpoint: si el broker no
     * pudo abrir su journal o su puerto, los servicios que dependen de el no
     * deben arrancar creyendo que esta.
     */
    @Bean
    public HealthIndicator artemis(EmbeddedActiveMQ broker) {
        return () -> broker.getActiveMQServer() != null && broker.getActiveMQServer().isActive()
                ? Health.up().withDetail("servidor", "activo").build()
                : Health.down().withDetail("servidor", "inactivo").build();
    }
}

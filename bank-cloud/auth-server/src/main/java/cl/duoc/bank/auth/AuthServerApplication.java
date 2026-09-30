package cl.duoc.bank.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Servidor de autorizacion OAuth 2.0 del banco. Ver {@link SeguridadAuthConfig}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}

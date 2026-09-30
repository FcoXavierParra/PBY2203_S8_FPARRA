package cl.duoc.bank.core;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Punto de entrada del modulo comun.
 *
 * Los tres BFF viven en paquetes propios (cl.duoc.bank.bff.web, .movil,
 * .cajero), asi que el escaneo por defecto de Spring Boot no alcanzaria a este
 * modulo. En vez de obligar a cada aplicacion a repetir tres anotaciones de
 * escaneo -y a que se desincronicen cuando alguna cambie-, el modulo declara
 * aqui como se auto-configura y cada BFF solo lo importa.
 */
@Configuration
@ComponentScan(basePackages = "cl.duoc.bank.core")
@EntityScan(basePackages = "cl.duoc.bank.core.dominio")
@EnableJpaRepositories(basePackages = "cl.duoc.bank.core.repositorio")
public class CoreConfig {
}

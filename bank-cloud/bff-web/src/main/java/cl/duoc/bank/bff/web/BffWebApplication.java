package cl.duoc.bank.bff.web;

import cl.duoc.bank.cliente.ClienteCuentasConfig;
import cl.duoc.bank.cliente.ClienteTransferenciasConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Backend for Frontend del canal web. Puerto 8081, sobre HTTPS.
 *
 * Lo que se importa aqui es la diferencia con la entrega anterior: antes era
 * CoreConfig -las entidades y repositorios del dominio, dentro de este mismo
 * proceso- y ahora es ClienteCuentasConfig, que no trae dominio sino un
 * cliente HTTP hacia ms-cuentas.
 *
 * No hace falta ninguna anotacion para registrarse en Eureka ni para pedir la
 * configuracion: tener spring-cloud-starter-netflix-eureka-client y
 * spring-cloud-starter-config en el classpath basta. @EnableEurekaClient
 * existio hasta Spring Cloud 2020 y hoy no es necesaria.
 */
@SpringBootApplication
@Import({ClienteCuentasConfig.class, ClienteTransferenciasConfig.class})
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}

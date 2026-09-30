package cl.duoc.bank.transferencias;

import cl.duoc.bank.eventos.EventosConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Microservicio de transferencias: el que inicia la saga.
 *
 * @EnableScheduling por el publicador del outbox, que cada medio segundo
 * revisa si quedan eventos por enviar al broker. Ver PublicadorOutbox.
 */
@SpringBootApplication
@EnableScheduling
// Los repositorios estan anidados en Repositorios; sin esto Spring Data no los ve.
@EnableJpaRepositories(considerNestedRepositories = true)
@Import(EventosConfig.class)
public class MsTransferenciasApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsTransferenciasApplication.class, args);
    }
}

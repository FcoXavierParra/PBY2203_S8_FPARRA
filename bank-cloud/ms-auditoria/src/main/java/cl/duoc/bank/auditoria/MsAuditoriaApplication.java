package cl.duoc.bank.auditoria;

import cl.duoc.bank.eventos.EventosConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
// El repositorio esta anidado en EventoRegistrado; sin esto Spring Data no lo ve.
@EnableJpaRepositories(considerNestedRepositories = true)
@Import(EventosConfig.class)
public class MsAuditoriaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAuditoriaApplication.class, args);
    }
}

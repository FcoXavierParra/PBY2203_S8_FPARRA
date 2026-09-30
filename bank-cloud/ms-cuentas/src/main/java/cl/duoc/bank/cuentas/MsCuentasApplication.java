package cl.duoc.bank.cuentas;

import cl.duoc.bank.core.CoreConfig;
import cl.duoc.bank.eventos.EventosConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Microservicio de cuentas: el Backend real del sistema.
 *
 * QUE CAMBIO RESPECTO DE LA SEMANA 5, Y POR QUE
 * ---------------------------------------------
 * En la Experiencia 2 los tres BFF declaraban bank-core como dependencia Maven
 * y llamaban a ConsultaService y OperacionService en proceso. La
 * retroalimentacion fue clara: "se recomienda exponer el modulo de dominio
 * compartido como un servicio Backend independiente y hacer que los tres BFF se
 * integren con el mediante llamadas HTTP".
 *
 * Esto es ese servicio. El cambio no es cosmetico:
 *
 *   - bank-core figura en el pom de ESTE modulo y de ningun otro. Los tres BFF
 *     perdieron el acceso a los repositorios JPA porque perdieron la
 *     dependencia; ya no es cuestion de disciplina, es que no compila.
 *   - Solo este proceso abre conexiones a la base. Los tres BFF no tienen
 *     siquiera driver en el classpath.
 *   - La agregacion -totales, promedios, conteo de movimientos- ocurre aqui y
 *     viaja resuelta. Ver el javadoc de FichaCuenta para por que no puede
 *     quedarse en el BFF una vez que la llamada es remota.
 *
 * El @Import es el mismo mecanismo que usaban los BFF en la entrega anterior:
 * bank-core vive en un paquete que no cuelga de este, asi que declara en
 * CoreConfig como se auto-configura -entidades, repositorios y servicios- y
 * quien lo use solo lo importa.
 */
@SpringBootApplication
@Import({CoreConfig.class, EventosConfig.class})
public class MsCuentasApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsCuentasApplication.class, args);
    }
}

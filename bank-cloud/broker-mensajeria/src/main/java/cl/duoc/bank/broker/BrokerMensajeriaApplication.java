package cl.duoc.bank.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * El broker JMS del ecosistema.
 *
 * Se excluye ArtemisAutoConfiguration a proposito. Esa autoconfiguracion esta
 * pensada para el caso contrario -una aplicacion que CONSUME un broker y, si
 * encuentra el servidor en el classpath, levanta uno embebido para pruebas- y
 * aqui chocaria con el servidor que BrokerConfig arma a mano, con su
 * persistencia, su seguridad y sus permisos. Dos servidores en el mismo
 * proceso peleando por el puerto 61616 dan un error que no menciona a ninguno
 * de los dos.
 */
@SpringBootApplication(exclude = ArtemisAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BrokerMensajeriaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerMensajeriaApplication.class, args);
    }
}

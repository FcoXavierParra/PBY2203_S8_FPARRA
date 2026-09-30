package cl.duoc.bank.bff.movil;

import cl.duoc.bank.cliente.ClienteCuentasConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * BFF del canal MOVIL. Puerto 8082.
 *
 * Sirve a una aplicacion de telefono: pantalla chica, red variable y bateria
 * finita. Cada campo que viaja de mas se paga en datos moviles del usuario, asi
 * que este BFF entrega lo minimo indispensable y ningun agregado.
 */
@SpringBootApplication
@Import(ClienteCuentasConfig.class)
public class BffMovilApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffMovilApplication.class, args);
    }
}

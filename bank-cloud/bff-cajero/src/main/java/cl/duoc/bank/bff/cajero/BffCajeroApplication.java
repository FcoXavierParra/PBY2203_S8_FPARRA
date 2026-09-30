package cl.duoc.bank.bff.cajero;

import cl.duoc.bank.cliente.ClienteCuentasConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * BFF del canal CAJERO AUTOMATICO. Puerto 8083.
 *
 * Sirve a un terminal fisico que ejecuta operaciones criticas -consultar saldo
 * y retirar efectivo- sin nadie mirando la pantalla mas que el cliente. Es el
 * canal con menos superficie expuesta y la autenticacion mas estricta de los
 * tres: no hay endpoints de listado, ni historial, ni datos personales.
 */
@SpringBootApplication
@Import(ClienteCuentasConfig.class)
public class BffCajeroApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffCajeroApplication.class, args);
    }
}

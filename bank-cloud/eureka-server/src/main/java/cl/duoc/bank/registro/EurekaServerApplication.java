package cl.duoc.bank.registro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Servidor de descubrimiento de servicios.
 *
 * QUE PROBLEMA RESUELVE
 * ---------------------
 * Los tres BFF necesitan llamar a ms-cuentas. Sin registro, cada uno tendria
 * escrita la direccion http://localhost:8090 en su configuracion, y mover el
 * servicio de puerto o levantar una segunda instancia obligaria a editar los
 * tres y reiniciarlos.
 *
 * Con Eureka los BFF piden "ms-cuentas" por nombre y el registro devuelve las
 * instancias vivas. Levantar una segunda instancia de ms-cuentas no requiere
 * tocar ningun BFF: aparece en el registro y el balanceo de carga del cliente
 * empieza a repartir entre las dos.
 *
 * POR QUE ESTE SERVIDOR NO SE REGISTRA A SI MISMO
 * -----------------------------------------------
 * register-with-eureka y fetch-registry van en false. Es el registro, no un
 * participante: no tiene a quien anunciarse ni a quien buscar. Con un solo nodo
 * dejarlos en true -que es el valor por defecto- hace que el servidor intente
 * replicarse consigo mismo y llene el log de errores de conexion durante el
 * arranque.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}

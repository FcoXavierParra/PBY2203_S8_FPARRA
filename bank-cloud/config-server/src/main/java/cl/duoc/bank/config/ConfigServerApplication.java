package cl.duoc.bank.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Servidor de configuracion centralizada.
 *
 * QUE PROBLEMA RESUELVE
 * ---------------------
 * En la entrega anterior cada BFF llevaba su application.properties dentro del
 * jar: el puerto, la base de datos, la duracion del token, el secreto de firma.
 * Cambiar la duracion de los tokens del canal movil obligaba a recompilar y
 * volver a desplegar ese servicio. Con cuatro servicios todavia se aguanta; la
 * guia plantea el escenario de cien, y ahi deja de aguantarse.
 *
 * Aqui la configuracion vive en un solo lugar y los servicios la piden al
 * arrancar. Lo que queda dentro de cada jar es lo minimo indispensable para
 * encontrar este servidor: su nombre y la direccion del config server.
 *
 * DE DONDE SALEN LOS ARCHIVOS: PERFIL 'native'
 * --------------------------------------------
 * Spring Cloud Config puede leer la configuracion desde un repositorio git, un
 * bucket S3 o el sistema de archivos local. La guia muestra git y S3; aqui se
 * usa el perfil 'native', que lee de classpath:/config-repo.
 *
 * Es una decision de alcance deliberada. Un repositorio git remoto haria que la
 * evidencia de esta entrega dependiera de que ese repositorio siga existiendo y
 * accesible al momento de corregir, y que el corrector tenga credenciales. Con
 * 'native' cualquiera que clone el proyecto levanta el ecosistema completo sin
 * red y obtiene exactamente la misma salida. En produccion seria git, que es lo
 * que da historial y revision por pull request a los cambios de configuracion.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}

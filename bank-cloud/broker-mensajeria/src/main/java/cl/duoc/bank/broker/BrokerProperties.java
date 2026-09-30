package cl.duoc.bank.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Lo que el Config Server le entrega al broker: donde escuchar, donde guardar,
 * cuanto reintentar y quien puede entrar.
 *
 * @param aceptor           direccion del conector JMS, por ejemplo tcp://localhost:61616
 * @param datos             carpeta del journal. Ahi sobreviven los mensajes a un reinicio.
 * @param maxIntentos       entregas de un mensaje antes de mandarlo a la DLQ
 * @param esperaReintentoMs espera antes de la primera reentrega; se duplica en las siguientes
 * @param usuarios          una cuenta por microservicio, cada una con UN rol
 */
@ConfigurationProperties(prefix = "bank.broker")
public record BrokerProperties(
        String aceptor,
        String datos,
        int maxIntentos,
        long esperaReintentoMs,
        List<Usuario> usuarios) {

    public record Usuario(String usuario, String clave, String rol) {
    }
}

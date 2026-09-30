package cl.duoc.bank.eventos;

import cl.duoc.bank.contrato.eventos.Topicos;
import cl.duoc.bank.contrato.eventos.TransferenciaAplicada;
import cl.duoc.bank.contrato.eventos.TransferenciaCerrada;
import cl.duoc.bank.contrato.eventos.TransferenciaRechazada;
import cl.duoc.bank.contrato.eventos.TransferenciaSolicitada;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

import java.util.Map;

/**
 * Como hablan con el broker los tres servicios de eventos. Cada uno la
 * importa con @Import(EventosConfig.class).
 *
 * TRES DECISIONES
 * ===============
 *
 * 1. JSON EN UN TextMessage, CON EL TIPO EN UNA PROPIEDAD
 *    Y no un ObjectMessage con el objeto Java serializado. Un ObjectMessage
 *    obliga a que quien lee tenga exactamente la misma clase en el classpath
 *    -es un contrato binario-, y la deserializacion de objetos Java arbitrarios
 *    que llegan por la red es una fuente clasica de vulnerabilidades. JSON se
 *    puede leer con cualquier cliente y se puede inspeccionar en la DLQ.
 *
 * 2. SUSCRIPCIONES DURABLES Y COMPARTIDAS (JMS 2.0)
 *    Durable: si el suscriptor esta caido, el broker le guarda los eventos y se
 *    los entrega cuando vuelve. Sin eso, un ms-auditoria reiniciado perderia
 *    todo lo que paso mientras no estaba.
 *    Compartida: varias conexiones con el MISMO nombre de suscripcion se
 *    reparten los mensajes en vez de recibir cada una su copia. Es el
 *    equivalente JMS de un "consumer group" de Kafka, y es lo que permite
 *    escalar ms-cuentas levantando una segunda instancia sin tocar nada: las
 *    dos se suscriben como "ms-cuentas" y el broker reparte.
 *    El nombre de la suscripcion es el del SERVICIO, no el de la instancia.
 *
 *    Y tiene que ser UNICO EN TODO EL BROKER, no solo dentro del topico: en
 *    Artemis una suscripcion compartida sin clientID es una cola que se llama
 *    exactamente como la suscripcion. La primera version uso "ms-auditoria"
 *    en los cuatro topicos; solo se creo la cola del primero que se conecto,
 *    los otros tres quedaron sin suscriptor, y ms-cuentas publicaba sus
 *    TransferenciaAplicada a un topico sin nadie escuchando. La saga quedaba
 *    en PENDIENTE sin un solo error en el log. Por eso la convencion es
 *    "servicio.hecho": ms-auditoria.aplicada, ms-transferencias.rechazada.
 *
 * 3. SESION TRANSACCIONADA EN EL CONSUMIDOR
 *    El mensaje se confirma al broker solo si el metodo del listener termina
 *    sin excepcion. Si lanza, rollback y reentrega; despues de los intentos
 *    configurados en el broker, a la DLQ. Es la garantia at-least-once: ningun
 *    evento se pierde por una falla a mitad de proceso, a cambio de que alguno
 *    pueda llegar dos veces. Por eso los consumidores son idempotentes.
 */
@Configuration
@EnableJms
public class EventosConfig {

    /** Nombre de la fabrica que usan todos los @JmsListener de topicos. */
    public static final String FABRICA_TOPICOS = "fabricaTopicos";

    @Bean
    public MessageConverter conversorEventos(ObjectMapper objectMapper) {
        MappingJackson2MessageConverter c = new MappingJackson2MessageConverter();
        c.setObjectMapper(objectMapper);
        c.setTargetType(MessageType.TEXT);
        c.setTypeIdPropertyName(Topicos.PROPIEDAD_TIPO);
        c.setTypeIdMappings(Map.of(
                "TransferenciaSolicitada", TransferenciaSolicitada.class,
                "TransferenciaAplicada", TransferenciaAplicada.class,
                "TransferenciaRechazada", TransferenciaRechazada.class,
                "TransferenciaCerrada", TransferenciaCerrada.class));
        return c;
    }

    @Bean(name = FABRICA_TOPICOS)
    public DefaultJmsListenerContainerFactory fabricaTopicos(
            DefaultJmsListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter conversorEventos) {

        DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
        // Los contenedores de escucha van sobre la fabrica NATIVA, sin la
        // cache que Spring Boot le pone delante para el JmsTemplate. La cache
        // guarda consumidores abiertos, y un contenedor que escala su numero
        // de consumidores con la carga necesita poder crearlos y cerrarlos.
        configurer.configure(f, ConnectionFactoryUnwrapper.unwrapCaching(connectionFactory));
        f.setMessageConverter(conversorEventos);
        f.setPubSubDomain(true);
        f.setSubscriptionDurable(true);
        f.setSubscriptionShared(true);
        f.setSessionTransacted(true);
        // Si el broker se cae, cada contenedor reintenta conectarse cada 3 s
        // por su cuenta. Nadie tiene que reiniciar el servicio.
        f.setRecoveryInterval(3000L);
        return f;
    }

    @Bean
    public PublicadorEventos publicadorEventos(JmsTemplate jmsTemplate) {
        return new PublicadorEventos(jmsTemplate);
    }
}

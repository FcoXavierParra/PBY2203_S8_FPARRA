package cl.duoc.bank.broker;

import cl.duoc.bank.contrato.eventos.Topicos;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.config.impl.SecurityConfiguration;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;
import org.apache.activemq.artemis.spi.core.security.jaas.InVMLoginModule;
import org.springframework.context.annotation.Bean;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El servidor Artemis: direcciones, reintentos, cola de mensajes muertos y
 * permisos.
 *
 * LAS DIRECCIONES SE DECLARAN, NO SE AUTOCREAN
 * ============================================
 * Artemis crea por defecto cualquier direccion a la que alguien publique. Es
 * comodo y es una trampa: un productor que escribe "banco.transferencia.aplicda"
 * crea un topico nuevo sin que nada avise, publica ahi, y el evento se pierde
 * porque nadie esta suscrito. La saga queda colgada en PENDIENTE y el log no
 * muestra ningun error. Aqui las cuatro direcciones estan declaradas y la
 * autocreacion apagada: publicar a un nombre mal escrito falla en el acto.
 *
 * REINTENTOS ACOTADOS Y COLA DE MENSAJES MUERTOS
 * ==============================================
 * Si un consumidor falla al procesar un evento -lanza una excepcion- su sesion
 * hace rollback y el broker lo reentrega. Eso cubre las fallas transitorias:
 * un bloqueo de base de datos, un reinicio a medias. Pero un mensaje que falla
 * SIEMPRE -un JSON corrupto, un tipo que nadie conoce- se reentregaria para
 * siempre y bloquearia a los que vienen detras. Es lo que la guia llama
 * "reintentos controlados": despues de maxIntentos el broker lo aparta en la
 * DLQ, donde un operador puede revisarlo, y la suscripcion sigue avanzando.
 *
 * La espera entre reentregas crece (1 s, 2 s, 4 s): reintentar en el mismo
 * milisegundo contra un recurso ocupado solo repite la colision.
 *
 * DETECCION DE DUPLICADOS EN EL BROKER
 * ====================================
 * Los productores marcan cada evento con _AMQ_DUPL_ID = su eventoId. El broker
 * recuerda los ultimos idCacheSize identificadores -en disco, sobreviven a un
 * reinicio- y descarta en silencio un segundo envio con el mismo id. Es la
 * cuarta estrategia de la guia, "desduplicacion en el intermediario", y cubre
 * el caso tipico del outbox: se publico, el servicio murio antes de marcar la
 * fila como publicada, y al volver la publica de nuevo.
 *
 * No reemplaza la idempotencia del consumidor, que sigue en ms-cuentas: la
 * cache es finita y un duplicado puede llegar por otro camino. Son dos capas y
 * la evidencia prueba las dos por separado.
 *
 * PERMISOS: CADA SERVICIO PUBLICA SOLO SUS PROPIOS HECHOS
 * ======================================================
 * Ver permisos(). La regla es que un evento solo puede publicarlo el servicio
 * que es duenio del hecho que describe. Si ms-auditoria quedara comprometido,
 * el atacante podria LEER todos los eventos del banco -eso ya lo puede hacer
 * por diseno- pero no podria publicar un "TransferenciaAplicada" falso para que
 * ms-transferencias diera por hecha una transferencia que nunca ocurrio.
 */
@Slf4j
@org.springframework.context.annotation.Configuration
public class BrokerConfig {

    /** Nombre de la cola de mensajes muertos. */
    public static final String DLQ = "DLQ";

    // Los roles. Uno por microservicio, porque cada uno tiene una funcion
    // distinta en la saga y por lo tanto permisos distintos.
    static final String ROL_SAGA = "saga-transferencias";
    static final String ROL_CUENTAS = "participante-cuentas";
    static final String ROL_AUDITOR = "auditor";
    static final String ROL_ADMIN = "administrador";

    @Bean(initMethod = "start", destroyMethod = "stop")
    public EmbeddedActiveMQ broker(BrokerProperties props) throws Exception {

        Configuration config = new ConfigurationImpl()
                .setName("broker-banco")
                // Persistente: un evento publicado mientras su consumidor esta
                // caido queda en disco y se entrega cuando vuelve, aunque entre
                // medio se reinicie el broker.
                .setPersistenceEnabled(true)
                .setJournalDirectory(props.datos() + "/journal")
                .setBindingsDirectory(props.datos() + "/bindings")
                .setPagingDirectory(props.datos() + "/paging")
                .setLargeMessagesDirectory(props.datos() + "/large")
                .setSecurityEnabled(true)
                .setJMXManagementEnabled(false)
                .setIDCacheSize(5000);

        config.addAcceptorConfiguration("jms", props.aceptor());

        // --- Direcciones: multicast = topico, cada suscripcion recibe copia.
        for (String topico : Topicos.TODOS) {
            config.addAddressConfiguration(new CoreAddressConfiguration()
                    .setName(topico)
                    .addRoutingType(RoutingType.MULTICAST));
        }
        // La DLQ es anycast: una cola comun donde se acumula lo que nadie pudo
        // procesar, no algo a lo que suscribirse.
        config.addAddressConfiguration(new CoreAddressConfiguration()
                .setName(DLQ)
                .addRoutingType(RoutingType.ANYCAST)
                .addQueueConfiguration(QueueConfiguration.of(DLQ)
                        .setAddress(DLQ)
                        .setRoutingType(RoutingType.ANYCAST)));

        // --- Reentregas
        config.addAddressSetting("#", new AddressSettings()
                .setMaxDeliveryAttempts(props.maxIntentos())
                .setRedeliveryDelay(props.esperaReintentoMs())
                .setRedeliveryMultiplier(2.0)
                .setMaxRedeliveryDelay(props.esperaReintentoMs() * 8)
                .setDeadLetterAddress(SimpleString.of(DLQ))
                .setAutoCreateAddresses(false)
                .setAutoCreateDeadLetterResources(false));

        // --- Permisos
        permisos().forEach(config::putSecurityRoles);

        // --- Usuarios. El login lo hace el modulo JAAS en memoria de Artemis;
        // los usuarios y sus claves llegan desde el Config Server.
        SecurityConfiguration usuarios = new SecurityConfiguration();
        for (BrokerProperties.Usuario u : props.usuarios()) {
            usuarios.addUser(u.usuario(), u.clave());
            usuarios.addRole(u.usuario(), u.rol());
        }

        EmbeddedActiveMQ servidor = new EmbeddedActiveMQ();
        servidor.setConfiguration(config);
        servidor.setSecurityManager(
                new ActiveMQJAASSecurityManager(InVMLoginModule.class.getName(), usuarios));

        log.info("Broker configurado: aceptor {}, {} topicos, DLQ tras {} intentos, {} usuarios",
                props.aceptor(), Topicos.TODOS.size(), props.maxIntentos(), props.usuarios().size());
        return servidor;
    }

    /**
     * La matriz de permisos, topico por topico.
     *
     * <pre>
     *                                  publica            consume
     *   banco.transferencia.solicitada saga               cuentas, auditor
     *   banco.transferencia.aplicada   cuentas            saga, auditor
     *   banco.transferencia.rechazada  cuentas            saga, auditor
     *   banco.transferencia.cerrada    saga               auditor
     *   DLQ                            (el broker)        administrador
     * </pre>
     *
     * En Artemis gana la coincidencia MAS ESPECIFICA, no la union de todas: si
     * un topico tiene su propia entrada, la de "#" no se le aplica. Por eso el
     * administrador aparece en cada fila y no solo en la general.
     *
     * Consumir exige ademas crear cola durable: una suscripcion compartida es,
     * del lado del broker, una cola con nombre colgada del topico, y la crea el
     * propio suscriptor la primera vez que se conecta.
     */
    static Map<String, Set<Role>> permisos() {
        return Map.of(
                Topicos.TRANSFERENCIA_SOLICITADA, roles(List.of(ROL_SAGA), List.of(ROL_CUENTAS, ROL_AUDITOR)),
                Topicos.TRANSFERENCIA_APLICADA, roles(List.of(ROL_CUENTAS), List.of(ROL_SAGA, ROL_AUDITOR)),
                Topicos.TRANSFERENCIA_RECHAZADA, roles(List.of(ROL_CUENTAS), List.of(ROL_SAGA, ROL_AUDITOR)),
                Topicos.TRANSFERENCIA_CERRADA, roles(List.of(ROL_SAGA), List.of(ROL_AUDITOR)),
                DLQ, roles(List.of(), List.of()),
                "#", roles(List.of(), List.of()));
    }

    private static Set<Role> roles(List<String> publican, List<String> consumen) {
        Set<Role> r = new HashSet<>();
        for (String p : publican) {
            r.add(rol(p, true, false));
        }
        for (String c : consumen) {
            r.add(rol(c, false, true));
        }
        // El administrador puede todo en todas partes: publicar a mano para la
        // prueba de duplicados, recorrer la DLQ y vaciarla.
        r.add(new Role(ROL_ADMIN, true, true, true, true, true, true, true, true, true, true));
        return r;
    }

    private static Role rol(String nombre, boolean publica, boolean consume) {
        //           nombre  send     consume  crDur    delDur crNoDur delNoDur manage browse crAddr delAddr
        return new Role(nombre, publica, consume, consume, false, false, false, false, false, false, false);
    }
}

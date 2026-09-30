package cl.duoc.bank.contrato.eventos;

import java.util.List;

/**
 * Los nombres de los topicos, en un solo lugar.
 *
 * Los comparten el broker -que los declara y les asigna permisos- y los tres
 * servicios que publican o se suscriben. Un nombre escrito a mano en dos
 * lugares es un evento que se publica en un topico y se espera en otro; aqui
 * eso no compila.
 *
 * UN TOPICO POR TIPO DE HECHO
 * ---------------------------
 * Y no un unico topico "transferencias" con todos los eventos mezclados. Asi
 * cada suscriptor recibe solo lo que le importa -ms-cuentas no tiene por que
 * ver los cierres de saga- y, sobre todo, los permisos se pueden dar por
 * topico: solo ms-cuentas puede publicar en .aplicada. Con un topico unico no
 * habria forma de impedir que ms-transferencias publicara una aplicacion falsa.
 *
 * La nomenclatura es dominio.entidad.hecho-en-participio: los eventos
 * describen algo que YA ocurrio, no una orden. Es la diferencia entre una
 * coreografia y una orquestacion: nadie le dice a ms-cuentas que haga algo; se
 * anuncia que alguien solicito una transferencia y ms-cuentas decide
 * reaccionar.
 */
public final class Topicos {

    /** ms-transferencias -> ms-cuentas, ms-auditoria */
    public static final String TRANSFERENCIA_SOLICITADA = "banco.transferencia.solicitada";

    /** ms-cuentas -> ms-transferencias, ms-auditoria */
    public static final String TRANSFERENCIA_APLICADA = "banco.transferencia.aplicada";

    /** ms-cuentas -> ms-transferencias, ms-auditoria */
    public static final String TRANSFERENCIA_RECHAZADA = "banco.transferencia.rechazada";

    /** ms-transferencias -> ms-auditoria. Cierra la saga, con o sin compensacion. */
    public static final String TRANSFERENCIA_CERRADA = "banco.transferencia.cerrada";

    public static final List<String> TODOS = List.of(
            TRANSFERENCIA_SOLICITADA, TRANSFERENCIA_APLICADA, TRANSFERENCIA_RECHAZADA, TRANSFERENCIA_CERRADA);

    /**
     * Propiedad JMS que dice a que record corresponde el cuerpo JSON.
     *
     * Lleva el nombre SIMPLE del tipo ("TransferenciaAplicada") y no el nombre
     * de la clase Java. Si viajara la clase, renombrar un paquete en un
     * servicio romperia la deserializacion en los otros, y el contrato quedaria
     * atado a como esta organizado el codigo de quien publica.
     */
    public static final String PROPIEDAD_TIPO = "_tipo";

    private Topicos() {
    }
}

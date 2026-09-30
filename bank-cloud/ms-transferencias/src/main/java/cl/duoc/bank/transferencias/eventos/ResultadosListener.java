package cl.duoc.bank.transferencias.eventos;

import cl.duoc.bank.contrato.eventos.Topicos;
import cl.duoc.bank.contrato.eventos.TransferenciaAplicada;
import cl.duoc.bank.contrato.eventos.TransferenciaRechazada;
import cl.duoc.bank.eventos.EventosConfig;
import cl.duoc.bank.transferencias.servicio.SagaTransferencias;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Los resultados que publica ms-cuentas, y el cierre de la saga.
 *
 * Una suscripcion por topico, cada una con nombre propio. Si se
 * levantaran dos instancias de ms-transferencias, se repartirian los
 * resultados igual que las de ms-cuentas se reparten las solicitudes.
 */
@Component
@RequiredArgsConstructor
public class ResultadosListener {

    private final SagaTransferencias saga;

    @JmsListener(destination = Topicos.TRANSFERENCIA_APLICADA, subscription = "ms-transferencias.aplicada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void alAplicarse(TransferenciaAplicada e) {
        saga.alAplicarse(e);
    }

    @JmsListener(destination = Topicos.TRANSFERENCIA_RECHAZADA, subscription = "ms-transferencias.rechazada",
            containerFactory = EventosConfig.FABRICA_TOPICOS)
    public void alRechazarse(TransferenciaRechazada e) {
        saga.alRechazarse(e);
    }
}

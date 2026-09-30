package cl.duoc.bank.core.carga;

import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.MovimientoAnual;
import cl.duoc.bank.core.dominio.Transaccion;
import cl.duoc.bank.core.repositorio.CuentaRepository;
import cl.duoc.bank.core.repositorio.MovimientoAnualRepository;
import cl.duoc.bank.core.repositorio.TransaccionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga los CSV oficiales del Banco XYZ en la base al arrancar cada BFF.
 *
 * DE DONDE SALEN LOS DATOS
 * ------------------------
 * De https://github.com/KariVillagran/bank_legacy_data, carpeta data/semana_3,
 * que es el dataset oficial vigente del repositorio (se verifico que no existe
 * una carpeta semana_4). Los archivos estan bajo src/main/resources/data y esa
 * es la ruta POR DEFECTO, sin necesidad de pasar ningun parametro.
 *
 * Que sea el valor por defecto no es un detalle. En la entrega anterior la
 * configuracion apuntaba a un dataset de una semana previa y los datos oficiales
 * quedaron en otra carpeta, alcanzables solo cambiando un parametro; la
 * retroalimentacion del docente lo marco como el principal punto a reforzar.
 * Aqui no hay parametro que cambiar: lo que se carga sin configurar nada es lo
 * oficial.
 *
 * TOLERANCIA A DATOS SUCIOS
 * -------------------------
 * El dataset viene con errores a proposito -montos vacios, edades ausentes,
 * tipo 'invalid' o '-1', y fechas en tres formatos distintos-. La carga
 * distingue dos cosas que no son lo mismo:
 *
 *   corregible : 03-04-2024 y 04/05/2024 se normalizan a 2024-04-03 y
 *                2024-05-04. El orden de los campos es inequivoco.
 *   perdido    : un monto o un id vacio descarta la fila, porque inventar un
 *                cero cambiaria los saldos que despues muestran los tres BFF.
 *
 * Los descartes se cuentan y se registran al terminar. Un BFF que sirve saldos
 * a un cajero automatico no puede callar cuantas filas no pudo leer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CargadorDatos {

    private static final DateTimeFormatter[] FORMATOS_FECHA = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    private final CuentaRepository cuentaRepository;
    private final TransaccionRepository transaccionRepository;
    private final MovimientoAnualRepository movimientoAnualRepository;

    @Value("${bank.datos.ruta:data}")
    private String ruta;

    /**
     * Se engancha a ApplicationReadyEvent y no a un @PostConstruct: hay que
     * esperar a que Hibernate haya creado las tablas.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cargar() {
        if (cuentaRepository.count() > 0) {
            log.info("La base ya tiene datos; no se recarga.");
            return;
        }
        log.info("Cargando el dataset oficial desde classpath:{}/", ruta);

        int cuentas = cargarCuentas();
        int transacciones = cargarTransacciones();
        int movimientos = cargarMovimientos();

        log.info("Carga lista: {} cuenta(s), {} transaccion(es), {} movimiento(s) anual(es)",
                cuentas, transacciones, movimientos);
    }

    // ------------------------------------------------------------- CUENTAS

    private int cargarCuentas() {
        List<Cuenta> lote = new ArrayList<>();
        int descartadas = 0;

        for (String[] c : leer("intereses.csv", 5)) {
            Long id = entero(c[0]);
            BigDecimal saldo = decimal(c[2]);
            // Sin id no hay clave, y sin saldo no hay cuenta que mostrar.
            if (id == null || saldo == null) {
                descartadas++;
                continue;
            }
            // Sin batch previo no hay intereses aplicados: el saldo inicial y el
            // final son el mismo, y las columnas que calcula la Experiencia 1
            // quedan nulas. Contra Oracle esas columnas SI vienen llenas.
            lote.add(new Cuenta(id, texto(c[1]), saldo, enteroInt(c[3]), tipoCuenta(c[4]),
                    null, null, saldo, null));
        }
        // Dos filas del dataset repiten cuenta_id; se queda la ultima.
        cuentaRepository.saveAll(lote);
        registrarDescartes("intereses.csv", descartadas);
        return (int) cuentaRepository.count();
    }

    // -------------------------------------------------------- TRANSACCIONES

    private int cargarTransacciones() {
        List<Transaccion> lote = new ArrayList<>();
        int descartadas = 0;

        for (String[] c : leer("transacciones.csv", 4)) {
            Long id = entero(c[0]);
            LocalDate fecha = fecha(c[1]);
            BigDecimal monto = decimal(c[2]);
            if (id == null || fecha == null || monto == null) {
                descartadas++;
                continue;
            }
            // Monto cero o negativo se descarta, con el mismo criterio que el
            // ItemProcessor del batch de la Experiencia 1: una transaccion de
            // monto no positivo no es una transaccion, y sumarla al resumen
            // falsearia los totales.
            //
            // La regla se repite aqui a proposito. Contra Oracle nunca hace
            // falta -el batch ya filtro esas filas antes de persistirlas-, pero
            // el montaje sin infraestructura lee los CSV crudos, y sin esto los
            // dos montajes mostrarian datos distintos para el mismo dataset.
            if (monto.compareTo(BigDecimal.ZERO) <= 0) {
                descartadas++;
                continue;
            }
            lote.add(new Transaccion(id, fecha, monto, tipoTransaccion(c[3]), false, null));
        }
        transaccionRepository.saveAll(lote);
        registrarDescartes("transacciones.csv", descartadas);
        return (int) transaccionRepository.count();
    }

    // ----------------------------------------------------------- ANUALES

    private int cargarMovimientos() {
        List<MovimientoAnual> lote = new ArrayList<>();
        int descartadas = 0;

        for (String[] c : leer("cuentas_anuales.csv", 5)) {
            Long cuentaId = entero(c[0]);
            LocalDate fecha = fecha(c[1]);
            BigDecimal monto = decimal(c[3]);
            if (cuentaId == null || fecha == null || monto == null) {
                descartadas++;
                continue;
            }
            // La descripcion si puede venir vacia: es texto de apoyo y no
            // participa en ningun total.
            lote.add(new MovimientoAnual(null, cuentaId, fecha, texto(c[2]), monto, texto(c[4]), false, null));
        }
        movimientoAnualRepository.saveAll(lote);
        registrarDescartes("cuentas_anuales.csv", descartadas);
        return (int) movimientoAnualRepository.count();
    }

    // ------------------------------------------------------------ LECTURA

    /** Devuelve las filas del CSV ya partidas, con la cabecera descartada. */
    private List<String[]> leer(String archivo, int columnas) {
        List<String[]> filas = new ArrayList<>();
        ClassPathResource recurso = new ClassPathResource(ruta + "/" + archivo);

        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {

            String linea = lector.readLine();   // cabecera
            while ((linea = lector.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                // -1 conserva las celdas vacias del final, que en este dataset
                // son informacion: una descripcion ausente no es lo mismo que
                // una fila mas corta.
                String[] celdas = linea.split(",", -1);
                if (celdas.length < columnas) {
                    continue;
                }
                filas.add(celdas);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + recurso.getPath(), e);
        }
        return filas;
    }

    private void registrarDescartes(String archivo, int descartadas) {
        if (descartadas > 0) {
            log.warn("{}: {} fila(s) descartada(s) por datos irrecuperables", archivo, descartadas);
        }
    }

    // ------------------------------------------------------------ PARSEO

    private static String texto(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Long entero(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer enteroInt(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            // El dataset trae edades imposibles (-1, 0). Se aceptan como
            // desconocidas en vez de botar la cuenta: la edad no afecta al saldo.
            return (n < 0 || n > 120) ? null : n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String v) {
        try {
            return new BigDecimal(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Normaliza los tres formatos de fecha del dataset. */
    private static LocalDate fecha(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String limpio = v.trim();
        for (DateTimeFormatter f : FORMATOS_FECHA) {
            try {
                return LocalDate.parse(limpio, f);
            } catch (RuntimeException ignorado) {
                // Se prueba el formato siguiente.
            }
        }
        return null;
    }

    private static String tipoCuenta(String v) {
        String t = texto(v);
        if (t == null) {
            return "desconocido";
        }
        String n = t.toLowerCase();
        return (n.equals("ahorro") || n.equals("prestamo")) ? n : "desconocido";
    }

    private static String tipoTransaccion(String v) {
        String t = texto(v);
        if (t == null) {
            return "desconocido";
        }
        String n = t.toLowerCase();
        return (n.equals("debito") || n.equals("credito")) ? n : "desconocido";
    }
}

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Abre UNA conexion a Oracle y dice si sirve. Nada mas.
 *
 * Por que existe
 * --------------
 * Levantar los tres BFF con credenciales equivocadas cuesta NUEVE intentos de
 * login: tres aplicaciones, cada una reintentando tres veces al inicializar su
 * pool. El perfil por defecto de Autonomous Database bloquea la cuenta ADMIN a
 * los diez intentos consecutivos, asi que dos corridas a ciegas la dejan
 * inaccesible justo cuando se la necesita. Eso paso.
 *
 * Esta sonda gasta UN intento y traduce el error a algo accionable. levantar.ps1
 * la ejecuta antes de lanzar nada cuando se pide el perfil oracle.
 *
 * Ademas comprueba que las tablas que los BFF esperan existan y tengan filas: el
 * perfil oracle usa ddl-auto=validate, asi que una tabla ausente tumbaria las
 * tres aplicaciones despues de haber conectado bien, con un error que no se
 * parece en nada a "falta poblar la base".
 *
 * Se lanza con el modo de fuente unica de Java, con ojdbc11 y oraclepki en el
 * classpath. La contrasena llega por variable de entorno y nunca se imprime.
 */
public class ProbarConexion {

    /** Tablas que los BFF mapean y que debe haber dejado la Experiencia 1. */
    private static final String[] TABLAS = {"CUENTA", "TRANSACCION", "MOVIMIENTO_ANUAL"};

    public static void main(String[] args) {

        String url = env("ORACLE_JDBC_URL");
        String usuario = env("ORACLE_USER");
        String clave = env("ORACLE_PASSWORD");

        try (Connection cn = DriverManager.getConnection(url, usuario, clave)) {

            System.out.println("  Conexion establecida.");
            System.out.println("  Motor: " + cn.getMetaData().getDatabaseProductVersion().split("\n")[0]);

            boolean faltaAlgo = false;
            for (String tabla : TABLAS) {
                try (Statement st = cn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabla)) {
                    rs.next();
                    long filas = rs.getLong(1);
                    System.out.printf("  %-20s %,d fila(s)%n", tabla, filas);
                    if (filas == 0) {
                        faltaAlgo = true;
                    }
                } catch (SQLException e) {
                    System.out.printf("  %-20s NO EXISTE%n", tabla);
                    faltaAlgo = true;
                }
            }

            if (faltaAlgo) {
                System.out.println();
                System.out.println("  AVISO: faltan tablas o vienen vacias. Los BFF arrancarian igual");
                System.out.println("  -o no, porque el perfil oracle usa ddl-auto=validate- pero no");
                System.out.println("  tendrian nada que servir. Poblar la base con el batch de la");
                System.out.println("  Experiencia 1:  correr_oracle.ps1 -LimpiarTablas");
                System.exit(2);
            }

        } catch (SQLException e) {
            String mensaje = e.getMessage() == null ? "" : e.getMessage();
            System.out.println("  NO SE PUDO CONECTAR.");
            System.out.println("  " + mensaje.split("\n")[0]);
            System.out.println();

            // Cada codigo tiene una causa distinta y una salida distinta.
            // Confundirlos cuesta intentos contra el limite de bloqueo.
            if (mensaje.contains("ORA-01017")) {
                System.out.println("  Usuario o contrasena incorrectos.");
                System.out.println("  En PowerShell la variable se define con COMILLAS SIMPLES:");
                System.out.println("    $env:ORACLE_PASSWORD = 'la-clave'");
                System.out.println("  Con dobles, PowerShell expande las $variables y backticks que");
                System.out.println("  haya dentro y envia una contrasena distinta de la escrita.");
            } else if (mensaje.contains("ORA-28000")) {
                System.out.println("  La cuenta esta BLOQUEADA por intentos fallidos acumulados.");
                System.out.println("  Se desbloquea desde la consola de Oracle Cloud. Resetear la");
                System.out.println("  contrasena tambien la desbloquea, asi que ese unico paso sirve");
                System.out.println("  aunque no se recuerde la clave.");
            } else if (mensaje.contains("ORA-28001")) {
                System.out.println("  La contrasena expiro. Cambiarla en la consola de Oracle Cloud.");
            } else if (mensaje.contains("ORA-12514")) {
                System.out.println("  El listener no conoce el servicio: la base esta DETENIDA.");
                System.out.println("  Free Tier la apaga tras dias sin uso. Iniciarla en la consola.");
            } else if (mensaje.contains("ORA-17957") || mensaje.contains("SSO")) {
                System.out.println("  No se pudo abrir el wallet. Falta oraclepki en el classpath,");
                System.out.println("  que es la libreria que sabe leer cwallet.sso.");
            }
            System.exit(1);
        }

        System.out.println();
        System.out.println("  Credenciales validas y datos presentes.");
    }

    private static String env(String nombre) {
        String v = System.getenv(nombre);
        if (v == null || v.isBlank()) {
            System.out.println("  ERROR: falta la variable de entorno " + nombre);
            System.exit(1);
        }
        return v;
    }
}

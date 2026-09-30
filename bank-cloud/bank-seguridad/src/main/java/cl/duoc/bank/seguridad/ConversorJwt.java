package cl.duoc.bank.seguridad;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Traduce un token del servidor de autorizacion a los permisos que Spring
 * Security entiende. Lo usan los tres BFF y los tres microservicios.
 *
 * SEMANA 8: reemplaza a TokenService y FiltroJwt. Este modulo ya no emite ni
 * firma nada, y no tiene en su poder ninguna clave capaz de hacerlo: la firma
 * la verifica Spring Security con la clave PUBLICA del auth-server
 * (/oauth2/jwks), y la audiencia y el emisor los exige la configuracion de
 * cada servicio. Cuando un token llega aqui ya es autentico y va dirigido a
 * este servicio; lo unico que queda es decidir que permite.
 *
 * Dos fuentes de permisos, porque hay dos clases de portador:
 *   - claim 'roles' -> ROLE_x    personas: CLIENTE, EJECUTIVO.
 *   - claim 'scope' -> SCOPE_x   maquinas: cuentas.leer, cajero.terminal...
 * Un servicio autoriza con hasRole o con hasAuthority("SCOPE_..."), segun a
 * quien atienda.
 */
public class ConversorJwt implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String CLAIM_CANAL = "canal";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_CUENTA = "cuenta";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> permisos = new ArrayList<>();
        for (String rol : lista(jwt, CLAIM_ROLES)) {
            permisos.add(new SimpleGrantedAuthority(rol.startsWith("ROLE_") ? rol : "ROLE_" + rol));
        }
        for (String scope : lista(jwt, "scope")) {
            permisos.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        // El nombre es el 'sub': la persona o la maquina. Es lo que aparece en
        // los logs de acceso denegado.
        return new JwtAuthenticationToken(jwt, permisos, jwt.getSubject());
    }

    /** El auth-server escribe 'scope' como lista; se acepta tambien el formato texto del estandar. */
    private static List<String> lista(Jwt jwt, String claim) {
        Object valor = jwt.getClaims().get(claim);
        if (valor instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        if (valor instanceof String s && !s.isBlank()) {
            return List.of(s.trim().split("\\s+"));
        }
        return List.of();
    }
}

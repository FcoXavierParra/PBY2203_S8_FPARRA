package cl.duoc.bank.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Servidor de autorizacion OAuth 2.0.
 *
 * QUE CAMBIA RESPECTO DE LAS SEMANAS 5 A 7
 * ----------------------------------------
 * Hasta la semana 7 cada BFF emitia sus propios JWT, firmados con HMAC. Eso
 * tenia dos debilidades que el propio TokenService dejaba anotadas: quien valida
 * un token HMAC tiene en su poder la misma clave que sirve para FIRMARLO, y la
 * autenticacion estaba repetida en tres lugares, cada uno con su login.
 *
 * Ahora:
 *   - Este es el UNICO componente que firma tokens, con una clave RSA privada
 *     que nunca sale de este proceso. Los BFF y los microservicios solo tienen
 *     la clave publica (/oauth2/jwks): pueden verificar, no falsificar.
 *   - El login de las personas ocurre AQUI, no en los BFF. Un BFF comprometido
 *     ya no ve contrasenas.
 *   - Las llamadas entre servicios dejan de usar un usuario y clave fijos
 *     (Basic) y pasan a tokens de corta duracion con un scope por operacion.
 *
 * LOS DOS FLUJOS
 * --------------
 * authorization_code + PKCE, para personas (canales web y movil). La
 * aplicacion del usuario no puede guardar un secreto, asi que en cada login
 * genera un verificador aleatorio y manda solo su hash; al canjear el codigo
 * tiene que presentar el verificador. Quien intercepte el codigo en la
 * redireccion no puede usarlo.
 *
 * client_credentials, para maquinas: los BFF cuando llaman a un microservicio,
 * y el cajero automatico cuando se presenta ante su BFF. No hay una persona
 * detras, y la maquina si puede guardar su secreto.
 *
 * AUDIENCIA: A QUIEN VA DIRIGIDO CADA TOKEN
 * -----------------------------------------
 * El claim 'aud' nombra al servidor de recursos que debe aceptar el token, y
 * cada uno rechaza los que no van dirigidos a el. Es lo que preserva la
 * separacion por canal de las semanas anteriores -un token del canal web no
 * sirve en el BFF movil- sin que cada canal tenga su propia clave: la firma es
 * una sola, lo que difiere es el destinatario.
 *   - persona: aud = el BFF de su canal (configurado por cliente).
 *   - maquina: aud = los microservicios de sus scopes ('cuentas.leer' -> ms-cuentas).
 */
@Slf4j
@Configuration
public class SeguridadAuthConfig {

    public static final String CLAIM_CANAL = "canal";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_CUENTA = "cuenta";

    /**
     * Cadena 1: los endpoints del protocolo (/oauth2/authorize, /oauth2/token,
     * /oauth2/jwks, /.well-known/...). Si una peticion de navegador llega a
     * /oauth2/authorize sin sesion, se la manda al formulario de login.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain protocolo(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer servidor = OAuth2AuthorizationServerConfigurer.authorizationServer();
        // Solo un navegador -que pide text/html explicitamente- va al formulario
        // de login. Sin ignorar */*, cualquier cliente de API que no declare lo
        // que acepta (curl, un RestClient) recibiria un 302 hacia una pagina
        // HTML en vez del 401 que corresponde.
        MediaTypeRequestMatcher navegador = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        navegador.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        http.securityMatcher(servidor.getEndpointsMatcher())
                .with(servidor, Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), navegador));
        return http.build();
    }

    /**
     * Cadena 2: el formulario de login (con proteccion CSRF, que aqui SI
     * corresponde: es un formulario de navegador con sesion) y la salud.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain login(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .build();
    }

    @Bean
    public PasswordEncoder encoder() {
        // Delegante: guarda el algoritmo junto al hash ({bcrypt}...). Sirve para
        // las claves de los usuarios y para los secretos de los clientes.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService usuarios(AuthProperties propiedades, PasswordEncoder encoder) {
        if (propiedades.usuarios().isEmpty()) {
            throw new IllegalStateException("El Config Server no entrego ningun usuario en bank.auth.usuarios");
        }
        return new InMemoryUserDetailsManager(propiedades.usuarios().stream()
                .map(u -> User.withUsername(u.usuario())
                        .password(encoder.encode(u.clave()))
                        .roles(u.roles().toArray(String[]::new))
                        .build())
                .toList());
    }

    @Bean
    public RegisteredClientRepository clientes(AuthProperties propiedades, PasswordEncoder encoder) {
        List<RegisteredClient> registrados = new ArrayList<>();
        for (AuthProperties.Cliente c : propiedades.clientes()) {
            registrados.add(switch (c.tipo()) {
                case PUBLICO -> publico(c);
                case MAQUINA -> maquina(c, propiedades, encoder);
            });
            log.info("Cliente registrado: {} ({}), token de {}", c.id(), c.tipo(), c.duracionToken());
        }
        return new InMemoryRegisteredClientRepository(registrados);
    }

    private static RegisteredClient publico(AuthProperties.Cliente c) {
        if (c.canal() == null || c.audiencia() == null || c.redirecciones().isEmpty()) {
            throw new IllegalStateException("El cliente publico " + c.id()
                    + " necesita canal, audiencia y al menos una redireccion");
        }
        return RegisteredClient.withId(c.id())
                .clientId(c.id())
                // Sin secreto: una aplicacion en manos del usuario no puede guardarlo.
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> uris.addAll(c.redirecciones()))
                .scopes(s -> s.addAll(c.scopes()))
                .clientSettings(ClientSettings.builder()
                        // Sin PKCE, un cliente publico no tendria como probar que
                        // el codigo es suyo. Se exige, no se ofrece.
                        .requireProofKey(true)
                        // Los clientes son del propio banco: no hay un tercero al
                        // que la persona deba autorizar expresamente.
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(c.duracionToken()).build())
                .build();
    }

    private static RegisteredClient maquina(AuthProperties.Cliente c, AuthProperties propiedades,
                                            PasswordEncoder encoder) {
        if (c.secreto() == null || c.secreto().isBlank() || c.scopes().isEmpty()) {
            throw new IllegalStateException("El cliente " + c.id() + " necesita secreto y scopes");
        }
        for (String scope : c.scopes()) {
            if (propiedades.recursoDe(scope).isEmpty()) {
                throw new IllegalStateException("El scope " + scope + " de " + c.id()
                        + " no pertenece a ningun servidor de recursos de bank.auth.recursos");
            }
        }
        return RegisteredClient.withId(c.id())
                .clientId(c.id())
                .clientSecret(encoder.encode(c.secreto()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(s -> s.addAll(c.scopes()))
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(c.duracionToken()).build())
                .build();
    }

    /**
     * Agrega a cada token de acceso lo que los servidores de recursos necesitan
     * para autorizar, y decide su audiencia.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> personalizarTokens(AuthProperties propiedades) {
        return contexto -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(contexto.getTokenType())) {
                return;
            }
            String clienteId = contexto.getRegisteredClient().getClientId();
            AuthProperties.Cliente cliente = propiedades.cliente(clienteId).orElseThrow();

            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(contexto.getAuthorizationGrantType())) {
                // Maquina: el token va dirigido a los microservicios de los scopes
                // que pidio y le fueron concedidos, y a ninguno mas.
                Set<String> audiencia = new LinkedHashSet<>();
                contexto.getAuthorizedScopes().forEach(s -> propiedades.recursoDe(s).ifPresent(audiencia::add));
                contexto.getClaims().audience(new ArrayList<>(audiencia));
                log.info("Token de servicio para '{}': scopes {} -> aud {}",
                        clienteId, contexto.getAuthorizedScopes(), audiencia);
                return;
            }

            // Persona: el titular, su cuenta y sus roles viajan FIRMADOS. Es la
            // leccion del IDOR de la semana 5: el BFF compara la cuenta pedida
            // contra este claim, nunca contra lo que diga la URL.
            String nombre = contexto.getPrincipal().getName();
            AuthProperties.Usuario usuario = propiedades.usuario(nombre).orElseThrow();
            if (!usuario.canales().contains(cliente.canal())) {
                log.warn("Token denegado: '{}' no esta habilitado para el canal {}", nombre, cliente.canal());
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED,
                        "El usuario no esta habilitado para el canal " + cliente.canal(), null));
            }
            List<String> roles = contexto.getPrincipal().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                    .toList();
            contexto.getClaims()
                    .audience(List.of(cliente.audiencia()))
                    .claim(CLAIM_CANAL, cliente.canal())
                    .claim(CLAIM_ROLES, roles);
            if (usuario.cuenta() != null) {
                contexto.getClaims().claim(CLAIM_CUENTA, usuario.cuenta());
            }
            log.info("Token de usuario para '{}' por {}: roles {}, cuenta {}, aud {}",
                    nombre, clienteId, roles, usuario.cuenta(), cliente.audiencia());
        };
    }

    /**
     * La clave de firma: RSA de 2048 bits, generada al arrancar y solo en memoria.
     *
     * Que se regenere en cada arranque es deliberado en este alcance: un reinicio
     * invalida todos los tokens emitidos, y como duran minutos, el costo es un
     * nuevo login. Los servidores de recursos no necesitan reconfigurarse:
     * cuando reciben un token con un 'kid' que no conocen, vuelven a leer
     * /oauth2/jwks. En produccion la clave viviria en un gestor de claves (KMS,
     * Vault) y se rotaria publicando la nueva junto a la anterior.
     */
    @Bean
    public JWKSource<SecurityContext> claveDeFirma() {
        KeyPair par = generarRsa();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) par.getPublic())
                .privateKey((RSAPrivateKey) par.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        log.info("Clave de firma RSA generada, kid {}", rsa.getKeyID());
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    private static KeyPair generarRsa() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no soporta RSA", e);
        }
    }

    @Bean
    public AuthorizationServerSettings ajustesServidor(AuthProperties propiedades) {
        return AuthorizationServerSettings.builder().issuer(propiedades.emisor()).build();
    }
}

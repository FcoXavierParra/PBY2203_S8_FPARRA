package cl.duoc.bank.cliente;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;

import java.util.List;

/**
 * Tokens de maquina para llamar a los microservicios (SEMANA 8).
 *
 * Reemplaza a la cabecera Basic fija de las semanas 6 y 7. Cada peticion que
 * sale de un BFF hacia un microservicio lleva ahora un token OAuth 2.0 pedido
 * al auth-server con client_credentials:
 *   - se pide la primera vez que hace falta y se reutiliza mientras dura (cinco
 *     minutos); se renueva solo un minuto antes de vencer;
 *   - trae solo los scopes de la registracion que corresponde al destino. El
 *     cliente de ms-cuentas pide 'cuentas.leer', el de ms-transferencias pide
 *     'transferencias.*': ninguno de los dos tokens sirve en el otro servicio.
 *
 * POR QUE AuthorizedClientServiceOAuth2AuthorizedClientManager
 * El gestor por defecto de Spring guarda los tokens en la sesion HTTP del
 * usuario, porque esta pensado para el caso en que el token es DE la persona.
 * Aqui el token es del BFF: no pertenece a ninguna peticion en particular, y
 * guardarlo por usuario haria que cada cliente del banco disparara su propio
 * pedido al auth-server. Este gestor lo guarda una sola vez, a nombre del BFF.
 */
@Configuration
public class TokensServicioConfig {

    /** A nombre de quien se guarda el token: del propio BFF, no de la persona que origino la llamada. */
    private static final Authentication BFF = UsernamePasswordAuthenticationToken.authenticated(
            "bff", null, List.of());

    @Bean
    public OAuth2AuthorizedClientManager tokensDeServicio(ClientRegistrationRepository registraciones,
                                                          OAuth2AuthorizedClientService almacen) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager gestor =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registraciones, almacen);
        gestor.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return gestor;
    }

    /**
     * Interceptor que agrega "Authorization: Bearer ..." con el token de la
     * registracion indicada. Si el microservicio responde 401, el token se
     * descarta y la siguiente llamada pide uno nuevo.
     */
    static OAuth2ClientHttpRequestInterceptor interceptor(OAuth2AuthorizedClientManager gestor,
                                                          OAuth2AuthorizedClientService almacen,
                                                          String registracion) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(gestor);
        interceptor.setClientRegistrationIdResolver(peticion -> registracion);
        interceptor.setPrincipalResolver(peticion -> BFF);
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(almacen));
        return interceptor;
    }
}

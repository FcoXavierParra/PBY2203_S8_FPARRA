package cl.duoc.bank.cliente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.client.RestClient;

/**
 * Arma el cliente de ms-transferencias. Solo lo importa bff-web: movil y
 * cajero no transfieren.
 *
 * Reutiliza el RestClient.Builder balanceado de ClienteCuentasConfig, asi que
 * bff-web tiene que importar las dos. La URI tambien es un nombre logico,
 * lb://ms-transferencias, resuelto contra Eureka.
 *
 * SEMANA 8: cada peticion lleva el token de la registracion 'ms-transferencias'
 * (scopes transferencias.crear y transferencias.leer), distinto del que el
 * mismo BFF usa para ms-cuentas. Ver TokensServicioConfig.
 */
@Configuration
public class ClienteTransferenciasConfig {

    @Bean
    public ClienteTransferencias clienteTransferencias(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager tokens,
            OAuth2AuthorizedClientService almacen,
            @Value("${bank.transferencias.url:lb://ms-transferencias}") String url,
            @Value("${bank.transferencias.timeout-conexion-ms:1000}") int timeoutConexion,
            @Value("${bank.transferencias.timeout-lectura-ms:2500}") int timeoutLectura) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutConexion);
        factory.setReadTimeout(timeoutLectura);

        return new ClienteTransferencias(builder.clone()
                .baseUrl(url)
                .requestFactory(factory)
                .requestInterceptor(TokensServicioConfig.interceptor(tokens, almacen, "ms-transferencias"))
                .build());
    }
}

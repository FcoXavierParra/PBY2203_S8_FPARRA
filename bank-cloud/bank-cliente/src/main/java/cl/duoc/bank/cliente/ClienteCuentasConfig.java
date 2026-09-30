package cl.duoc.bank.cliente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.client.RestClient;


/**
 * Arma el cliente de ms-cuentas. Los tres BFF la importan.
 *
 * Es el mismo mecanismo que CoreConfig usaba en la entrega anterior: el modulo
 * declara aqui como se auto-configura y quien lo use solo lo importa, en vez
 * de repetir la misma cablera en tres aplicaciones y que se desincronicen.
 */
@Configuration
@Import(TokensServicioConfig.class)
public class ClienteCuentasConfig {

    /**
     * El balanceador se engancha aqui.
     *
     * La anotacion @LoadBalanced le agrega al builder un interceptor que, ante
     * una URI con esquema lb://, resuelve el nombre del servicio contra las
     * instancias registradas en Eureka y elige una. Sin ella, lb://ms-cuentas
     * seria un esquema desconocido y la llamada fallaria con un error de URI
     * malformada, no con uno de descubrimiento, que es lo que despista.
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder cuentasRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public ClienteCuentas clienteCuentas(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager tokens,
            OAuth2AuthorizedClientService almacen,
            @Value("${bank.interno.url:lb://ms-cuentas}") String url,
            @Value("${bank.interno.timeout-conexion-ms:1000}") int timeoutConexion,
            @Value("${bank.interno.timeout-lectura-ms:2500}") int timeoutLectura) {

        // ------------------------------------------------------------------
        // Timeouts de transporte
        // ------------------------------------------------------------------
        // Sin esto una llamada HTTP espera indefinidamente, y un ms-cuentas
        // colgado -no caido: colgado- dejaria a los tres canales bloqueados sin
        // que el Circuit Breaker llegara nunca a contar un fallo, porque desde
        // su punto de vista la llamada sigue en curso. El timeout es lo que
        // convierte "no responde" en un error que el circuito pueda medir.
        //
        // El de lectura es deliberadamente menor que el
        // slow-call-duration-threshold de los canales mas tolerantes, para que
        // el corte lo dé el transporte y no se acumulen llamadas colgadas.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutConexion);
        factory.setReadTimeout(timeoutLectura);

        // ------------------------------------------------------------------
        // Credencial de servicio (SEMANA 8: token OAuth 2.0, ya no Basic)
        // ------------------------------------------------------------------
        // Cada peticion lleva el token de la registracion 'ms-cuentas' del BFF.
        // Que scopes trae depende del canal: web y movil piden cuentas.leer, el
        // cajero ademas cuentas.operar. Ver la registracion en cada bff-*.yml y
        // TokensServicioConfig.
        RestClient rest = builder.clone()
                .baseUrl(url)
                .requestFactory(factory)
                .requestInterceptor(TokensServicioConfig.interceptor(tokens, almacen, "ms-cuentas"))
                .build();

        return new ClienteCuentas(rest);
    }
}

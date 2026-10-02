package app.seats;

import io.undertow.server.handlers.RequestBufferingHandler;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Read small booking bodies without occupying a blocking servlet worker. */
@Configuration(proxyBeanMethods = false)
class HttpRequestBuffering {
    @Bean
    WebServerFactoryCustomizer<UndertowServletWebServerFactory> requestBodyBuffering() {
        // Four pooled 1 KiB buffers per request at most. Larger bodies retain normal streaming.
        // This wrapper runs before ServletInitialHandler dispatches to the worker pool.
        return factory -> factory.addDeploymentInfoCustomizers(deployment ->
                deployment.addInitialHandlerChainWrapper(new RequestBufferingHandler.Wrapper(4)));
    }
}

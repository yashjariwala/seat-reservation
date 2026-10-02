package app.seats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Incomplete bodies must not consume every servlet worker and block health requests. */
@EnabledIfEnvironmentVariable(named = "SEAT_TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${SEAT_TEST_DATABASE_URL}", "server.undertow.threads.worker=4",
        "ADMIN_KEY=body-buffer-test-admin"})
class HttpBodyBufferingIntegrationTest {
    @LocalServerPort int port;

    @Test void incompleteSmallBodiesLeaveWorkersAvailableAndRemainReadable() throws Exception {
        var sockets = new ArrayList<Socket>();
        String body = "{\"user_id\":\"buffer-test\"}";
        try {
            for (int i = 0; i < 8; i++) {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(5000);
                sockets.add(socket);
                String headers = "POST /auth/token HTTP/1.1\r\nHost: localhost\r\n"
                        + "X-Admin-Key: body-buffer-test-admin\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write((headers + body.substring(0, body.length() - 1))
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            // Allow the I/O threads to deliver the partial bodies before checking worker availability.
            Thread.sleep(250);
            var client = HttpClient.newHttpClient();
            var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/actuator/health/liveness")).timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            for (Socket socket : sockets) {
                socket.getOutputStream().write('}');
                socket.getOutputStream().flush();
            }
            for (Socket socket : sockets) {
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertThat(reader.readLine()).contains(" 200 ");
                assertThat(reader.lines().reduce("", (a, b) -> a + b)).contains("\"token\"");
            }
        } finally {
            for (Socket socket : sockets) socket.close();
        }
    }
}

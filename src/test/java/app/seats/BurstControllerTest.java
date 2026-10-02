package app.seats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BurstControllerTest {
    @Test void onlyFixedPresetsAreAccepted() {
        assertThat(BurstController.spec("demo").requests()).isEqualTo(2000);
        assertThat(BurstController.spec("full").requests()).isEqualTo(20000);
        assertThatThrownBy(() -> BurstController.spec("unbounded")).isInstanceOf(ApiError.class);
    }

    @Test void onlyOneRunCanStartAndFailedRunsStillHaveACooldown() throws Exception {
        ShowController shows = mock(ShowController.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(shows.create(anyString(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Test dependency unavailable");
        });
        BurstController controller = new BurstController(shows, mock(Auth.class), new ObjectMapper(), "private-test-key", 300);
        var event = mock(WebServerInitializedEvent.class, RETURNS_DEEP_STUBS);
        when(event.getWebServer().getPort()).thenReturn(18081);
        controller.serverReady(event);
        try {
            assertThat(controller.start(new BurstController.Start("demo")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> controller.start(new BurstController.Start("full")))
                    .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code).isEqualTo("burst_running"));
            assertThat(controller.state().toString()).doesNotContain("private-test-key");
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var run = (Map<?, ?>) controller.state().get("run");
                assertThat(run.get("status")).isEqualTo("failed");
                assertThat(((Number)run.get("finished_at")).longValue()).isPositive();
            });
            assertThatThrownBy(() -> controller.start(new BurstController.Start("demo")))
                    .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        } finally { release.countDown(); controller.close(); }
    }
}

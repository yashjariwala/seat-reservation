package app.seats;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObsTest {
    @Test void refreshWaitsForScrapeAndConcurrentScrapesNeverSeeDuplicateLabels() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("show_id")).thenReturn("show1");
        when(row.getString("id")).thenReturn("show1");
        when(row.getString("status")).thenReturn("available");
        when(row.getLong("n")).thenReturn(100L);
        when(row.getLong("total_seats")).thenReturn(100L);
        AtomicInteger queries = new AtomicInteger();
        doAnswer(invocation -> {
            ((RowCallbackHandler)invocation.getArgument(1)).processRow(row);
            queries.incrementAndGet();
            return null;
        }).when(db).query(anyString(), any(RowCallbackHandler.class));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            Obs obs = new Obs(registry, db);
            obs.refreshSeatGauges();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try {
                Future<?> scrape = pool.submit(() -> {
                    obs.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), new MockHttpServletResponse(), (req, res) -> {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                        registry.scrape();
                    });
                    return null;
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> refresh = pool.submit(obs::refreshSeatGauges);
                await().atMost(Duration.ofSeconds(5)).until(() -> queries.get() == 2);
                assertThat(refresh.isDone()).isFalse();
                release.countDown();
                scrape.get(5, TimeUnit.SECONDS); refresh.get(5, TimeUnit.SECONDS);
                Future<?> writer = pool.submit(() -> { for (int i = 0; i < 300; i++) obs.refreshSeatGauges(); });
                Future<?>[] readers = new Future<?>[3];
                for (int i = 0; i < readers.length; i++) readers[i] = pool.submit(() -> {
                    for (int j = 0; j < 300; j++) obs.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), new MockHttpServletResponse(),
                            (req, res) -> assertThat(registry.scrape()).contains("show_id=\"show1\""));
                    return null;
                });
                writer.get(15, TimeUnit.SECONDS);
                for (Future<?> reader : readers) reader.get(15, TimeUnit.SECONDS);
            } finally { release.countDown(); pool.shutdownNow(); }
        } finally { registry.close(); }
    }
}

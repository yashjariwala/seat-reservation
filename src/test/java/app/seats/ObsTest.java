package app.seats;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.ResultSet;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObsTest {
    @Test void unhandledFailuresLog500AndCommittedResponsesKeepTheirActualStatus() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("access");
        var captured = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        captured.start(); logger.addAppender(captured);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            Obs obs = new Obs(registry, mock(JdbcTemplate.class));
            var request = new MockHttpServletRequest("POST", "/shows/test/reserve");
            request.addHeader("X-Request-Id", "fault-test");
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> obs.doFilter(request, response, (req, res) -> {
                throw new ServletException("Injected failure");
            })).isInstanceOf(ServletException.class);
            assertThat(captured.list.get(0).getMDCPropertyMap()).containsEntry("status", "500")
                    .containsEntry("request_id", "fault-test").containsEntry("outcome", "server_error")
                    .containsEntry("error_type", "ServletException");
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

            var committed = new MockHttpServletResponse(); committed.setStatus(201); committed.setCommitted(true);
            assertThatThrownBy(() -> obs.doFilter(new MockHttpServletRequest("POST", "/shows/test/reserve"), committed,
                    (req, res) -> { throw new java.io.IOException("Client disconnected after response started"); }))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(captured.list.get(1).getMDCPropertyMap()).containsEntry("status", "201");
        } finally { logger.detachAppender(captured); captured.stop(); registry.close(); MDC.clear(); }
    }
    @Test void gaugesKeepTheirIdentityAndMissingStatusesBecomeZero() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        java.util.concurrent.atomic.AtomicBoolean sold = new java.util.concurrent.atomic.AtomicBoolean();
        when(row.getString("show_id")).thenReturn("show1");
        when(row.getString("status")).thenAnswer(i -> sold.get() ? "confirmed" : "available");
        when(row.getLong("n")).thenReturn(100L);
        when(row.getLong("total_seats")).thenReturn(100L);
        doAnswer(i -> { ((RowCallbackHandler)i.getArgument(1)).processRow(row); return null; })
                .when(db).query(anyString(), any(RowCallbackHandler.class));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            Obs obs = new Obs(registry, db);
            obs.refreshSeatGauges();
            var available = registry.get("seats").tags("show_id", "show1", "status", "available").gauge();
            int meters = registry.getMeters().size();
            assertThat(available.value()).isEqualTo(100);
            sold.set(true); obs.refreshSeatGauges();
            assertThat(registry.get("seats").tags("show_id", "show1", "status", "available").gauge()).isSameAs(available);
            assertThat(available.value()).isZero();
            assertThat(registry.get("seats").tags("show_id", "show1", "status", "confirmed").gauge().value()).isEqualTo(100);
            assertThat(registry.getMeters()).hasSize(meters);
        } finally { registry.close(); }
    }

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

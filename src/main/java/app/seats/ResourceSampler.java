package app.seats;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Independent of HTTP workers and of the seat-gauge scheduler; never queries PostgreSQL. */
@Component
@ConditionalOnProperty(name = "resource.sampling.enabled", havingValue = "true", matchIfMissing = true)
public class ResourceSampler {
    private static final Logger log = LoggerFactory.getLogger("resources");
    private final DataSource dataSource;
    private final long interval;
    private final String instance = UUID.randomUUID().toString();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "resource-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private long previousNanos = System.nanoTime();
    private long previousCpu = cpuTime();

    ResourceSampler(DataSource dataSource, @Value("${resource.sample.interval-ms:5000}") long interval) {
        this.dataSource = dataSource;
        this.interval = Math.max(1000, interval);
    }

    @PostConstruct void start() {
        executor.scheduleWithFixedDelay(this::sample, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy void stop() {
        executor.shutdownNow();
    }

    void sample() {
        try {
            long now = System.nanoTime();
            long cpu = cpuTime();
            double elapsed = (now - previousNanos) / 1e9;
            var memory = ManagementFactory.getMemoryMXBean();
            long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
            var event = log.atInfo().addKeyValue("instance_id", instance)
                    .addKeyValue("pid", ProcessHandle.current().pid())
                    .addKeyValue("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime())
                    .addKeyValue("sample_interval_seconds", elapsed)
                    .addKeyValue("heap_used_bytes", memory.getHeapMemoryUsage().getUsed())
                    .addKeyValue("heap_max_bytes", memory.getHeapMemoryUsage().getMax())
                    .addKeyValue("nonheap_used_bytes", memory.getNonHeapMemoryUsage().getUsed())
                    .addKeyValue("direct_used_bytes", direct)
                    .addKeyValue("gc_time_ms_total", ManagementFactory.getGarbageCollectorMXBeans().stream()
                            .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum());
            if (cpu >= 0 && previousCpu >= 0) {
                event.addKeyValue("cpu_cores_used", (cpu - previousCpu) / 1e9 / elapsed);
                event.addKeyValue("cpu_time_ns_total", cpu);
            }
            Long containerMemory = containerMemory();
            if (containerMemory != null) event.addKeyValue("container_memory_bytes", containerMemory);
            if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                var pool = hikari.getHikariPoolMXBean();
                event.addKeyValue("db_active", pool.getActiveConnections())
                        .addKeyValue("db_idle", pool.getIdleConnections())
                        .addKeyValue("db_waiting", pool.getThreadsAwaitingConnection());
            }
            previousCpu = cpu;
            previousNanos = now;
            event.log("resource_sample");
        } catch (RuntimeException e) {
            // A diagnostic failure must not permanently cancel future scheduled samples.
            log.warn("resource_sample_failed", e);
        }
    }

    private static long cpuTime() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
    }

    private static Long containerMemory() {
        for (String file : new String[]{"/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes"}) {
            try {
                return Long.parseLong(Files.readString(Path.of(file)).trim());
            } catch (Exception ignored) {
                // Non-Linux hosts and runtimes without an accessible container memory counter omit this field.
            }
        }
        return null;
    }
}

package app.seats;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResourceSamplerTest {
    @Test void normalizesV1AndV2ThrottleTimeUnits() {
        var v2 = ResourceSampler.parseCpuStat("usage_usec 999\nnr_periods 100\nnr_throttled 70\nthrottled_usec 123456\n");
        var v1 = ResourceSampler.parseCpuStat("nr_periods 100\nnr_throttled 70\nthrottled_time 123456789\n");
        assertThat(v1).isEqualTo(v2);
        assertThat(v2).containsEntry("cpu_throttled_usec", 123456L)
                .containsEntry("cpu_periods", 100L).containsEntry("cpu_throttled_periods", 70L);
    }

    @Test void unavailableThrottleCountersAreNotReportedAsZero() {
        assertThat(ResourceSampler.parseCpuStat("usage_usec 123\nuser_usec 100\nsystem_usec 23\n")).isEmpty();
    }
}

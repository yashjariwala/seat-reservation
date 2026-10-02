package app.seats;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AuthTest {
    @Test void malformedTokenSuffixesCannotBeIgnored() {
        Auth auth = new Auth("test-secret", "admin");
        String token = auth.issue("user");
        for (String malformed : java.util.List.of(token + ".", token + "..", "." + token, token + ".extra", "", "x", "x.y"))
            assertThatThrownBy(() -> auth.userId("Bearer " + malformed)).isInstanceOf(ApiError.class);
        assertThat(auth.userId("Bearer " + token)).isEqualTo("user");
    }
    @Test void reusedSignersPreserveTokensAndRejectTamperingAcrossThreads() throws Exception {
        Auth shared = new Auth("test-secret", "admin");
        Auth independent = new Auth("test-secret", "admin");
        var workers = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                jobs.add(() -> {
                    for (int i = 0; i < 100; i++) {
                        String user = "user-" + thread + "-" + i;
                        String token = shared.issue(user);
                        assertThat(independent.userId("Bearer " + token)).isEqualTo(user);
                        assertThat(shared.userId("Bearer " + token)).isEqualTo(user);
                        String tampered = shared.issue("other").split("\\.")[0] + "." + token.split("\\.")[1];
                        assertThatThrownBy(() -> shared.userId("Bearer " + tampered)).isInstanceOf(ApiError.class);
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(jobs)) result.get();
        } finally { workers.shutdownNow(); }
    }
}

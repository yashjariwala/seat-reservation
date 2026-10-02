import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** External, synchronized single-seat storm. JDK 17+, no dependencies. No booking retries. */
public class HotSeat {
    static String field(String body, String name) {
        var match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!match.find()) throw new IllegalStateException("Missing response field: " + name);
        return match.group(1);
    }
    static HttpRequest request(String base, String path, String body, String header, String value) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(180));
        if (header != null) builder.header(header, value);
        return body == null ? builder.GET().build() : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    static String adminKey() throws Exception {
        String key = System.getenv("ADMIN_KEY");
        if (key == null && Files.exists(Path.of(".env"))) {
            for (String line : Files.readAllLines(Path.of(".env"))) {
                if (line.startsWith("ADMIN_KEY=")) {
                    key = line.substring(10).trim();
                    if (key.length() >= 2 && ((key.startsWith("\"") && key.endsWith("\""))
                            || (key.startsWith("'") && key.endsWith("'")))) key = key.substring(1, key.length()-1);
                }
            }
        }
        if (key == null || key.isBlank()) throw new IllegalStateException("Set ADMIN_KEY in the environment or git-ignored .env");
        return key;
    }
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0].replaceAll("/+$", "") : "http://localhost:8080";
        int buyers = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        if (buyers < 2 || buyers > 20000) throw new IllegalArgumentException("buyers must be 2..20000");
        String admin = adminKey(), prefix = UUID.randomUUID().toString().substring(0, 8);
        ExecutorService io = Executors.newFixedThreadPool(32), setup = Executors.newFixedThreadPool(16);
        try {
            HttpClient[] clients = new HttpClient[(buyers+63)/64];
            for (int i=0; i<clients.length; i++) clients[i] = HttpClient.newBuilder().executor(io)
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(30)).build();
            var created = clients[0].send(request(base, "/shows", "{\"name\":\"exact-hot-"+prefix
                    +"\",\"seats\":[\"A12\"],\"price_paise\":25000}", "X-Admin-Key", admin), HttpResponse.BodyHandlers.ofString());
            if (created.statusCode()!=201) throw new IllegalStateException("Show setup failed: HTTP " + created.statusCode());
            String show = field(created.body(), "id");
            System.out.printf("show=%s buyers=%d target=%s%nPreparing distinct buyers (outside measured storm)...%n", show,buyers,base);
            var jobs = new ArrayList<Future<HttpRequest>>();
            for (int i=0;i<buyers;i++) {
                final int index=i;
                jobs.add(setup.submit(() -> {
                    var client=clients[index/64];
                    var token=client.send(request(base,"/auth/token","{\"user_id\":\"hot-"+prefix+"-"+index+"\"}",
                            "X-Admin-Key",admin),HttpResponse.BodyHandlers.ofString());
                    if(token.statusCode()!=200) throw new IllegalStateException("Token setup failed: HTTP "+token.statusCode());
                    return request(base,"/shows/"+show+"/reserve","{\"seats\":[\"A12\"],\"idempotency_key\":\"hot-"+index+"\"}",
                            "Authorization","Bearer "+field(token.body(),"token"));
                }));
            }
            HttpRequest[] requests=new HttpRequest[buyers];
            for(int i=0;i<buyers;i++) requests[i]=jobs.get(i).get();
            // Warm all connections before t=0. This avoids measuring token minting / TLS setup as the storm.
            var warm=new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for(var client:clients) warm.add(client.sendAsync(request(base,"/actuator/health/liveness",null,null,null),HttpResponse.BodyHandlers.ofString()));
            CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)).join();
            for(var f:warm) if(f.join().statusCode()!=200) throw new IllegalStateException("Warm-up health failed");
            System.out.println("Warm-up protocols: "+warm.stream().map(f->f.join().version()).distinct().toList());
            var distribution=new ConcurrentSkipListMap<String,AtomicInteger>();
            AtomicInteger confirmed=new AtomicInteger(),declined=new AtomicInteger(),errors=new AtomicInteger();
            var done=new ArrayList<CompletableFuture<Void>>();
            long start=System.nanoTime();
            for(int i=0;i<buyers;i++) done.add(clients[i/64].sendAsync(requests[i],HttpResponse.BodyHandlers.ofString()).handle((response,error)->{
                String outcome;
                if(error!=null){errors.incrementAndGet();Throwable cause=error; while(cause.getCause()!=null) cause=cause.getCause();
                    outcome="network_error "+cause.getClass().getSimpleName()+": "+Objects.toString(cause.getMessage(), "no detail");}
                else if(response.statusCode()==201 && response.body().contains("\"confirmed\"")){confirmed.incrementAndGet();outcome="201 confirmed";}
                else if(response.statusCode()==409 && response.body().contains("\"seat_taken\"")){declined.incrementAndGet();outcome="409 seat_taken";}
                else{errors.incrementAndGet();outcome="HTTP "+response.statusCode()+" unexpected";}
                distribution.computeIfAbsent(outcome,k->new AtomicInteger()).incrementAndGet();return null;
            }));
            double submission=(System.nanoTime()-start)/1e9;
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).join();
            double elapsed=(System.nanoTime()-start)/1e9;
            var state=clients[0].send(request(base,"/shows/"+show,null,null,null),HttpResponse.BodyHandlers.ofString());
            System.out.printf("submission_span_seconds=%.3f completion_seconds=%.3f%n",submission,elapsed);
            distribution.forEach((outcome,n)->System.out.println(outcome+" = "+n));
            System.out.println("final_state="+state.body());
            boolean inventory=state.statusCode()==200
                    && Pattern.compile("\"available\"\\s*:\\s*0").matcher(state.body()).find()
                    && Pattern.compile("\"held\"\\s*:\\s*0").matcher(state.body()).find()
                    && Pattern.compile("\"confirmed\"\\s*:\\s*1").matcher(state.body()).find()
                    && Pattern.compile("\"total_seats\"\\s*:\\s*1").matcher(state.body()).find();
            boolean passed=confirmed.get()==1 && declined.get()==buyers-1 && errors.get()==0 && inventory && submission<=1;
            System.out.println(passed?"PASS: one winner, every loser cleanly declined, submitted within one second":"FAIL: exact storm acceptance criteria not met");
            System.out.println("Submission timing measures client dispatch, not verified arrival timing at the service.");
            if(!passed) System.exit(1);
        } finally {setup.shutdownNow();io.shutdownNow();}
    }
}

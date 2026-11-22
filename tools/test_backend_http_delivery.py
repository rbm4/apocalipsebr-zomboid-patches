"""Exercise the production HTTP adapter against a loopback server, never real credentials."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_backend_bridge_hooks import FILES as ENGINE_STUBS

ROOT = Path(__file__).resolve().parents[1]
GAME_JAR = Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")
HARNESS = r"""
package zombie.network;
import zombie.network.ApocBRBackendBridge.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class HttpDeliveryTest {
    static int checks;
    static final String KEY="fixture-integration-token";
    static final String ACK="{\"id\":\"12345678-1234-4234-8234-123456789abc\"}";
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static void eventually(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()){
            if(System.nanoTime()>deadline)throw new AssertionError("fixture wait timed out");
            Thread.sleep(1);
        }
    }
    record Reply(int status,String body,String retryAfter,long bodyDelayMillis){}
    record Received(String body,String key,String contentType,long nanos){}
    static class Stub implements AutoCloseable {
        final Queue<Reply> replies=new ConcurrentLinkedQueue<>();
        final List<Received> received=Collections.synchronizedList(new ArrayList<>());
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger redirected=new AtomicInteger();
        Stub()throws Exception {
            server.setExecutor(executor);
            server.createContext("/api/v1/radio/events",exchange->{
                try {
                    String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                    received.add(new Received(body,exchange.getRequestHeaders().getFirst("X-API-Key"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),System.nanoTime()));
                    Reply reply=replies.poll();if(reply==null)reply=new Reply(200,ACK,null,0);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Location","/redirect-target");
                    if(reply.retryAfter()!=null)exchange.getResponseHeaders().set("Retry-After",reply.retryAfter());
                    byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(),bytes.length);
                    if(reply.bodyDelayMillis()>0)Thread.sleep(reply.bodyDelayMillis());
                    exchange.getResponseBody().write(bytes);
                }catch(Exception ignored){}finally{exchange.close();}
            });
            server.createContext("/redirect-target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
            server.start();
        }
        URI endpoint(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/radio/events");}
        void reply(int status,String body){replies.add(new Reply(status,body,null,0));}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    static ApocBRBackendHttpDelivery delivery(Stub stub,List<String> diagnostics,long timeoutMillis){
        return new ApocBRBackendHttpDelivery(new ApocBRBackendHttpDelivery.Configuration(stub.endpoint(),KEY,
                Duration.ofSeconds(2),Duration.ofMillis(timeoutMillis),Duration.ofMillis(1250)),diagnostics::add);
    }
    static Event event(int id,String message){return new Event(3,"pve","pz-engine","fixture:"+id,
            EventType.PLAYER_CHAT,1000,"1993-07-09T13:20",Map.of("characterName","Ana","text",message),9000L);}
    static DeliveryFailure failure(ApocBRBackendHttpDelivery delivery,List<Event> batch)throws Exception {
        try{delivery.deliver(batch);throw new AssertionError("expected failure");}
        catch(DeliveryFailure failure){checks++;return failure;}
    }
    static void partialProgress()throws Exception {
        try(var stub=new Stub()){
            stub.reply(200,ACK);stub.reply(503,"{\"private\":\"do not log\"}");
            List<String> diagnostics=new ArrayList<>();
            try(var delivery=delivery(stub,diagnostics,3000)){
                List<Event> batch=List.of(event(1,"hello"),event(2,"Olá \"rádio\"\\\n"));
                check(!failure(delivery,batch).permanent,"503 retries");
                delivery.deliver(batch);
                check(stub.received.size()==3,"partial retry request count");
                check(stub.received.get(1).body().equals(stub.received.get(2).body()),"retry identical bytes and IDs");
                check(!stub.received.get(0).body().equals(stub.received.get(2).body()),"acknowledged event not replayed");
                for(Received request:stub.received){
                    JSONObject body=new JSONObject(request.body());
                    check(request.key().equals(KEY)&&request.contentType().contains("application/json"),"auth and JSON headers");
                    check(body.opt("publicPayloadJson") instanceof String,"payload is JSON string, not nested object");
                    check(!body.has("schemaVersion")&&!body.has("publicPayload")&&!body.has("seasonId"),"exact endpoint field mapping");
                    check(body.getLong("occurredAt")==1000&&body.getLong("expiresAt")==9000,"capture timestamps retained");
                }
                JSONObject last=new JSONObject(stub.received.get(2).body());
                check(new JSONObject(last.getString("publicPayloadJson")).getString("text").equals("Olá \"rádio\"\\\n"),"unicode and escaping roundtrip");
                check(stub.received.get(1).nanos()-stub.received.get(0).nanos()>=TimeUnit.MILLISECONDS.toNanos(1100),"requests paced below API quota");
                check(delivery.status().acknowledged()==2&&delivery.status().requests()==3,"per-event acknowledgment counters");
                check(diagnostics.contains("HTTP_503"),"existing error-code diagnostic retained");
                check(diagnostics.getFirst().equals("batch collected events=2"),"collection logged before packing");
                check(diagnostics.stream().filter(s->s.startsWith("packed event=")).count()==2,"packing logged once per event, not on replay");
                int bytes=stub.received.get(1).body().getBytes(StandardCharsets.UTF_8).length;
                check(diagnostics.contains("packed event=2/2 type=PLAYER_CHAT bytes="+bytes),"packing reports actual UTF-8 wire bytes");
                check(diagnostics.stream().filter(s->s.startsWith("sending request=")).count()==3,"each HTTP attempt logged including retry");
                check(diagnostics.stream().filter(s->s.startsWith("response request=")).count()==3,"HTTP statuses and elapsed time logged");
                check(diagnostics.contains("delivery failed code=HTTP_503 permanent=false retryAfterMs=0"),"retryable failure logged");
                check(diagnostics.contains("batch resumed remaining=1"),"retry logs only unacknowledged remainder");
                check(diagnostics.stream().filter(s->s.startsWith("acknowledged request=")).count()==2,"only valid acknowledgments logged");
                check(diagnostics.getLast().equals("batch complete events=2 rejected=0"),"completion logged after acknowledgment");
                String logs=String.join("\n",diagnostics);
                check(!logs.contains(KEY)&&!logs.contains("hello")&&!logs.contains("Ana")
                        &&!logs.contains("do not log")&&!logs.contains("private")&&!logs.contains("http://"),"logs exclude credentials, payload, names, endpoints and response bodies");
            }
        }
    }
    static void protocolFailures()throws Exception {
        for(int status:new int[]{401,403,404,405,302}){
            try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),3000)){
                stub.reply(status,"{}");
                check(failure(delivery,List.of(event(status,"message"))).permanent,"route/auth failure terminal");
                check(stub.redirected.get()==0,"credentials not forwarded through redirects");
            }
        }
        try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),3000)){
            stub.replies.add(new Reply(429,"{}","120",0));
            DeliveryFailure error=failure(delivery,List.of(event(429,"message")));
            check(!error.permanent&&error.retryAfterMillis==120000,"429 cooldown respects Retry-After");
        }
        try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),3000)){
            stub.reply(409,"{}");
            var batch=List.of(event(409,"message"));
            check(!failure(delivery,batch).permanent,"concurrent duplicate 409 retried in fresh HTTP request");
            delivery.deliver(batch);
            check(stub.received.get(0).body().equals(stub.received.get(1).body()),"409 stable replay");
        }
        for(String body:new String[]{"{}","{\"id\":\"not-a-uuid\"}","<html>error</html>","x".repeat(9000)}){
            try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),3000)){
                stub.reply(200,body);
                DeliveryFailure error=failure(delivery,List.of(event(9,"message")));
                check(!error.permanent&&delivery.status().acknowledged()==0,"malformed/oversized response not acknowledged");
            }
        }
        try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),200)){
            stub.replies.add(new Reply(200,ACK,null,2000));
            check(failure(delivery,List.of(event(10,"message"))).getMessage().equals("REQUEST_TIMEOUT"),"whole-body deadline");
        }
    }
    static void rejectionsAndWorker()throws Exception {
        try(var stub=new Stub()){
            stub.reply(400,"{\"error\":\"invalid observation\"}");
            var delivery=delivery(stub,new ArrayList<>(),3000);
            var bridge=new ApocBRBackendBridge("pve",new Settings(4,2,Duration.ofMillis(1),Duration.ofMillis(1),Duration.ofMillis(2)));
            var context=new Context(1000,"1993-07-09T13:20",null);
            bridge.publishChat(context,new PublicCharacter("Ana","Ana"),"first");
            bridge.publishChat(context,new PublicCharacter("Ana","Ana"),"second");
            bridge.start(delivery);
            eventually(()->bridge.status().delivered()==1);
            bridge.close();check(bridge.awaitTermination(Duration.ofSeconds(3)),"worker/client shutdown");
            check(bridge.status().rejectedDelivery()==1&&delivery.status().rejected()==1,"400 counted separately from delivery");
            check(stub.received.size()==2,"400 does not poison remaining valid events");
        }
        try(var stub=new Stub();var delivery=delivery(stub,new ArrayList<>(),3000)){
            delivery.deliver(List.of(event(1,"x".repeat(5000))));
            check(delivery.rejectedInBatch()==1&&stub.received.isEmpty(),"4000-character payload limit enforced before HTTP");
        }
        try(var stub=new Stub()){
            stub.reply(401,"{}");
            var bridge=new ApocBRBackendBridge("pve",Settings.defaults());
            bridge.publishChat(new Context(1000,null,null),new PublicCharacter("Ana","Ana"),"message");
            bridge.start(delivery(stub,new ArrayList<>(),3000));
            eventually(()->bridge.state()==State.FAILED);
            check(bridge.awaitTermination(Duration.ofSeconds(3))&&bridge.status().inFlight()==1,"auth failure preserves outstanding batch");
            check(bridge.status().lastDeliveryError().equals("HTTP_401")&&stub.received.size()==1,"no auth retry storm");
            bridge.close();
        }
        try(var stub=new Stub()){
            stub.replies.add(new Reply(200,ACK,null,2000));
            var bridge=new ApocBRBackendBridge("pve",Settings.defaults());
            bridge.publishChat(new Context(1000,null,null),new PublicCharacter("Ana","Ana"),"message");
            bridge.start(delivery(stub,new ArrayList<>(),3000));
            eventually(()->!stub.received.isEmpty());
            bridge.close();
            check(bridge.awaitTermination(Duration.ofSeconds(2))&&bridge.status().delivered()==0
                    &&bridge.status().inFlight()==1,"shutdown cancels stalled HTTP and retains pending event");
        }
    }
    static void startup()throws Exception {
        try(var stub=new Stub()){
            GameServer.server=true;
            System.setProperty("apocbr.bridge.enabled","true");
            System.setProperty("apocbr.bridge.serverKey"," PVE ");
            // Missing endpoint/credential must not install an unusable bridge.
            ApocBRBackendBridgeHooks.initialize();
            check(ApocBRBackendBridgeHooks.current()==null,"missing HTTP configuration fails closed");
            System.setProperty("apocbr.bridge.endpoint",stub.endpoint().toString());
            System.setProperty("apocbr.bridge.apiKey",KEY);
            ApocBRBackendBridgeHooks.initialize();
            var bridge=ApocBRBackendBridgeHooks.current();
            check(bridge!=null&&bridge.state()==State.RUNNING,"startup installs HTTP worker");
            bridge.publishChat(new Context(1000,null,null),new PublicCharacter("Ana","Ana"),"message");
            eventually(()->bridge.status().delivered()==1);
            check(ApocBRBackendBridgeHooks.httpStatus().acknowledged()==1,"startup uses real adapter");
            ApocBRBackendBridgeHooks.shutdown();
            check(bridge.awaitTermination(Duration.ofSeconds(3)),"startup adapter terminates");
        }
    }
    static void configuration(){
        for(String url:new String[]{"http://example.org/events","https://user:pass@example.org/events","https://example.org/events?key=x","https://example.org/events#x"}){
            try{new ApocBRBackendHttpDelivery.Configuration(URI.create(url),KEY,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(1250));
                throw new AssertionError("invalid endpoint accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        var config=new ApocBRBackendHttpDelivery.Configuration(URI.create("https://example.org/api/v1/radio/events"),KEY,
                Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(1250));
        check(!config.toString().contains(KEY),"configuration string redacts credential");
    }
    static void quietAndBrokenLogger()throws Exception {
        System.setProperty("apocbr.bridge.logDelivery","false");
        try(var stub=new Stub()){
            stub.reply(503,"private backend error");
            List<String> diagnostics=new ArrayList<>();
            try(var delivery=delivery(stub,diagnostics,3000)){
                var batch=List.of(event(1,"private message"));
                failure(delivery,batch);
                delivery.deliver(batch);
                check(diagnostics.equals(List.of("HTTP_503")),"quiet mode retains errors and suppresses progress");
            }
        } finally { System.clearProperty("apocbr.bridge.logDelivery"); }
        try(var stub=new Stub();var delivery=new ApocBRBackendHttpDelivery(
                new ApocBRBackendHttpDelivery.Configuration(stub.endpoint(),KEY,Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofMillis(1250)),
                message->{throw new IllegalStateException("broken log sink");})){
            delivery.deliver(List.of(event(1,"message")));
            check(delivery.status().acknowledged()==1,"logging failure cannot interrupt ingestion");
        }
    }
    public static void main(String[] args)throws Exception {
        configuration();partialProgress();protocolFailures();rejectionsAndWorker();startup();quietAndBrokenLogger();
        System.out.println("Passed "+checks+" production HTTP delivery assertions.");
    }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-http-delivery-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in ENGINE_STUBS.items():
            if relative == "BridgeHooksTest.java":
                continue
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        harness = work / "HttpDeliveryTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources.append(harness)
        sources.extend(ROOT / "42.21.0/src/zombie/network" / name for name in (
            "ApocBRBackendBridge.java", "ApocBRBackendBridgeHooks.java", "ApocBRBackendHttpDelivery.java"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "--add-modules", "jdk.httpserver",
                        "-cp", str(GAME_JAR), "-d", str(output), "@" + str(args)], check=True, timeout=60)
        environment = dict(os.environ)
        for name in ("APOCBR_BRIDGE_ENDPOINT", "APOCBR_BRIDGE_API_KEY"):
            environment.pop(name, None)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "--add-modules", "jdk.httpserver", "-cp",
                        str(output) + ";" + str(GAME_JAR), "zombie.network.HttpDeliveryTest"],
                       env=environment, check=True, timeout=60)


if __name__ == "__main__":
    main()

"""42.22.2 production API bridge: loopback HTTP and real Kahlua, no backend requests."""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = Path('Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar')
STUBS = {
    'zombie/core/Core.java': '''package zombie.core;
public class Core { public static boolean debug = false; public static boolean IS_DEV = false; public static boolean noSave; private static final Core instance=new Core(); public static Core getInstance(){return instance;} public boolean isNoSave(){return noSave;} }''',
    'zombie/world/moddata/GlobalModData.java': '''package zombie.world.moddata;
public final class GlobalModData { public static final GlobalModData instance=new GlobalModData(); public static int saves; public static boolean fail; public void save() throws java.io.IOException { saves++; if(fail) throw new java.io.IOException("fixture"); } }''',
    'zombie/network/GameServer.java': '''package zombie.network;
public class GameServer { public static boolean server = true; }''',
    'zombie/debug/DebugLog.java': '''package zombie.debug;
public class DebugLog { public static void log(String message) {} }''',
    'zombie/Lua/LuaManager.java': '''package zombie.Lua;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
public class LuaManager {
 public static final Platform platform = J2SEPlatform.getInstance();
 public static final KahluaTable env = platform.newTable();
 public static final KahluaThread thread = new KahluaThread(System.out, platform, env);
 public static final LuaCaller caller = new LuaCaller(new KahluaConverterManager());
 static { thread.debugOwnerThread = Thread.currentThread(); }
}''',
}
HARNESS = r'''
package zombie.apocbr.api;
import com.sun.net.httpserver.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import zombie.Lua.LuaManager;
import zombie.network.GameServer;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.expose.LuaJavaClassExposer;

public class ApiBridgeTest {
 static int checks;
 static final String UUID_VALUE="12345678-1234-4234-8234-123456789abc";
 static final String PARAMS="{\"runtimeUuid\":\""+UUID_VALUE+"\",\"sourceServerId\":2,\"steamId\":\"76561198000000001\"}";
 static final URI URI_VALUE=URI.create("http://127.0.0.1/test");
 static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
 static void eventually(BooleanSupplier done)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
  while(!done.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("wait timed out");Thread.sleep(2);}
 }
 static void invalid(String input){
  try{ApocBrApiBridge.Endpoint.CHARACTER_BALANCE.resolve(input);throw new AssertionError("accepted invalid input");}
  catch(RuntimeException expected){checks++;}
 }
 static void parameters(){
  URI uri=ApocBrApiBridge.Endpoint.CHARACTER_BALANCE.resolve(PARAMS);
  check(uri.toString().equals("https://plataforma.apocalipse.cloud/api/v1/game/characters/"+UUID_VALUE+"/balance?sourceServerId=2&steamId=76561198000000001"),"exact route");
  check(ApocBrApiBridge.CHARACTER_LOGIN.resolve(PARAMS).toString().equals("https://plataforma.apocalipse.cloud/api/v1/game/characters/"+UUID_VALUE+"/bind?sourceServerId=2&steamId=76561198000000001"),"exact login registration route");
  check("POST".equals(ApocBrApiBridge.CHARACTER_LOGIN.method())&&"GET".equals(ApocBrApiBridge.CHARACTER_BALANCE.method()),"Java enum owns HTTP method");
  for(String input:List.of("{}","[]",PARAMS+" {}",PARAMS.replace(UUID_VALUE,"../../admin"),
    PARAMS.replace("76561198000000001","76561198000000001&admin=true"),PARAMS.replace(":2,",":0,"),
    PARAMS.replace(":2,",":2.5,"),PARAMS.replace(":2,",":9007199254740991.1,"),
    PARAMS.replace("\"sourceServerId\":2","\"sourceServerId\":\"2\""),
    PARAMS.replace("}",",\"url\":\"https://evil.example\"}"),"x".repeat(1025)))invalid(input);
  check(Double.valueOf(15000).equals(ApocBrApiBridge.Endpoint.CHARACTER_BALANCE.decode("{\"balance\":15000}").get("balance")),"balance decode");
  for(String body:List.of("{}","{\"balance\":\"15000\"}","{\"balance\":1e309}","{\"balance\":1} []")){
   try{ApocBrApiBridge.Endpoint.CHARACTER_BALANCE.decode(body);throw new AssertionError("accepted bad response");}
   catch(RuntimeException expected){checks++;}
  }
 }

 static void debit(){
  String params=PARAMS.substring(0,PARAMS.length()-1)+",\"amount\":25,\"reason\":\"market.purchase\",\"idempotencyKey\":\"order:1\"}";
  check(ApocBrApiBridge.CHARACTER_DEBIT.resolve(params).toString().contains("/debit?sourceServerId=2&steamId=76561198000000001"),"debit fixed route and server identity");
  check("POST".equals(ApocBrApiBridge.CHARACTER_DEBIT.method()),"debit POST");
  check(ApocBrApiBridge.CHARACTER_CREDIT.resolve(params).toString().contains("/credit?sourceServerId=2&steamId=76561198000000001"),"credit fixed route");
  check(ApocBrApiBridge.CHARACTER_CREDIT.body(params).equals(ApocBrApiBridge.CHARACTER_DEBIT.body(params)),"seller credit uses same bounded idempotent mutation body");
  check("POST".equals(ApocBrApiBridge.CHARACTER_CREDIT.method()),"seller credit POST");
  org.json.JSONObject body=new org.json.JSONObject(ApocBrApiBridge.CHARACTER_DEBIT.body(params));
  check(body.length()==3&&body.getLong("amount")==25&&body.getString("reason").equals("market.purchase")&&body.getString("idempotencyKey").equals("order:1"),"debit body contains amount, reason, stable key without item reference");
  for(String invalid:List.of(params.replace("\"amount\":25","\"amount\":0"),params.replace("\"amount\":25","\"amount\":1.5"),params.replace("\"amount\":25","\"amount\":1000000001"),params.replace("market.purchase","bad\\nreason"),params.replace("order:1","bad/key"),PARAMS)){
   try{ApocBrApiBridge.CHARACTER_DEBIT.resolve(invalid);throw new AssertionError("invalid debit accepted");}catch(RuntimeException expected){checks++;}
  }
  Map<String,Object> result=ApocBrApiBridge.CHARACTER_DEBIT.decode("{\"balance\":75,\"transactionId\":\"tx:1\",\"idempotencyKey\":\"order:1\"}");
  check(result.get("transactionId").equals("tx:1")&&result.get("idempotencyKey").equals("order:1"),"debit transaction receipt decoded");
  try{ApocBrApiBridge.CHARACTER_DEBIT.decode("{\"balance\":75}");throw new AssertionError("missing receipt accepted");}catch(RuntimeException expected){checks++;}
  check(CourierItemCodec.encode(null)==null&&CourierItemCodec.decode("invalid")==null&&CourierItemCodec.decode("A".repeat(1400000))==null,"codec rejects missing, malformed and oversized blobs");
 }
 static class Fake implements ApiDispatcher.Transport {
  final List<String> methods=new CopyOnWriteArrayList<>();
  final List<Long> sent=new CopyOnWriteArrayList<>();
  final AtomicBoolean closed=new AtomicBoolean();
  public ApiDispatcher.Reply send(URI uri){sent.add(System.nanoTime());return new ApiDispatcher.Reply(200,"{\"balance\":15000}",null,0);}
  public ApiDispatcher.Reply send(URI uri,String method){methods.add(method);return send(uri);}
  public void close(){closed.set(true);}
 }
 static void bounded()throws Exception{
  AtomicLong clock=new AtomicLong(1);
  Fake fake=new Fake();
  try(ApiDispatcher dispatcher=new ApiDispatcher(fake,1500,clock::get)){
   check(dispatcher.submit("first",URI_VALUE)==null,"first admission");
   check("INVALID_METHOD".equals(dispatcher.submit("bad",URI_VALUE,"DELETE")),"unsupported methods never enter queue");
   check("THROTTLED".equals(dispatcher.submit("spam",URI_VALUE)),"immediate spam rejected");
   for(int i=1;i<32;i++){clock.addAndGet(1_500_000_000L);check(dispatcher.submit("r"+i,URI_VALUE)==null,"bounded admission");}
   clock.addAndGet(1_500_000_000L);
   check("QUEUE_FULL".equals(dispatcher.submit("overflow",URI_VALUE)),"capacity includes all outstanding");
   clock.addAndGet(31_000_000_000L);
   dispatcher.start();
   List<ApiDispatcher.Result> results=new ArrayList<>();
   eventually(()->{ApiDispatcher.Result r;while((r=dispatcher.poll())!=null)results.add(r);return results.size()==32;});
   check(results.stream().allMatch(r->"QUEUE_TIMEOUT".equals(r.error())),"stale requests expire without HTTP");
   check(fake.sent.isEmpty(),"expired backlog sends no requests");
  }
  eventually(fake.closed::get);check(fake.closed.get(),"worker transport closed eventually");
  Fake pacing=new Fake();
  try(ApiDispatcher dispatcher=new ApiDispatcher(pacing,1500,System::nanoTime)){
   dispatcher.start();check(dispatcher.submit("a",URI_VALUE)==null,"paced first");
   Thread.sleep(1510);check(dispatcher.submit("b",URI_VALUE)==null,"paced second");
   eventually(()->pacing.sent.size()==2);
   check(pacing.sent.get(1)-pacing.sent.get(0)>=1_490_000_000L,"wire spacing");
  }
 }
 static void cooldown()throws Exception{
  AtomicInteger attempts=new AtomicInteger();
  ApiDispatcher.Transport transport=new ApiDispatcher.Transport(){
   public ApiDispatcher.Reply send(URI uri){attempts.incrementAndGet();return new ApiDispatcher.Reply(429,null,"HTTP_429",60_000);}
   public void close(){}
  };
  try(ApiDispatcher dispatcher=new ApiDispatcher(transport,1500,System::nanoTime)){
   dispatcher.start();dispatcher.submit("limited",URI_VALUE);
   AtomicReference<ApiDispatcher.Result> result=new AtomicReference<>();
   eventually(()->{result.set(dispatcher.poll());return result.get()!=null;});
   check("HTTP_429".equals(result.get().error()),"429 result");
   check("BACKEND_COOLDOWN".equals(dispatcher.submit("spam",URI_VALUE)),"cooldown admission");
   check(attempts.get()==1,"no automatic retries");
  }
 }
 static void concurrentAdmission()throws Exception{
  try(ApiDispatcher dispatcher=new ApiDispatcher(new Fake(),1500,System::nanoTime)){
   AtomicInteger accepted=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);
   List<Thread> callers=new ArrayList<>();
   for(int i=0;i<100;i++)callers.add(Thread.ofVirtual().start(()->{
    try{start.await();if(dispatcher.submit("r",URI_VALUE)==null)accepted.incrementAndGet();}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
   }));
   start.countDown();for(Thread caller:callers)caller.join();
   check(accepted.get()==1,"concurrent spam shares one admission allowance");
  }
 }
 static void install(ApiBridgeLifecycle bridge)throws Exception{
  Field field=ApiBridgeLifecycle.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,bridge);
 }
 static void callbacks()throws Exception{
  Fake fake=new Fake();
  try(ApiDispatcher dispatcher=new ApiDispatcher(fake,1500,System::nanoTime)){
   dispatcher.start();install(new ApiBridgeLifecycle(dispatcher));
   LuaJavaClassExposer exposer=new LuaJavaClassExposer(new KahluaConverterManager(),LuaManager.platform,LuaManager.env){
    public boolean shouldExpose(Class<?> type){return type==ApocBrApiBridge.class||type==ApocBrApiBridge.Endpoint.class;}
   };
   exposer.exposeLikeJavaRecursively(ApocBrApiBridge.class,LuaManager.env);
   exposer.exposeLikeJavaRecursively(ApocBrApiBridge.Endpoint.class,LuaManager.env);
   LuaClosure call=LuaCompiler.loadstring("return ApocBrApiBridge.request(ApocBrApiBridge.CHARACTER_LOGIN, '"+PARAMS+"', function(r) result = r end)","request-fixture",LuaManager.env);
   Object[] returned=LuaManager.caller.pcall(LuaManager.thread,call,new Object[]{});
   check(Boolean.TRUE.equals(returned[0])&&returned[1] instanceof String,"real Kahlua invokes facade and enum constant");
   check(LuaManager.env.rawget("result")==null,"callback is asynchronous");
   eventually(()->{ApiBridgeLifecycle.update();return LuaManager.env.rawget("result")!=null;});
   KahluaTable result=(KahluaTable)LuaManager.env.rawget("result");
   check(Boolean.TRUE.equals(result.rawget("ok"))&&Double.valueOf(15000).equals(result.rawget("balance")),"Lua receives decoded balance");
   check(returned[1].equals(result.rawget("requestId")),"callback correlation");
   check(fake.methods.equals(List.of("POST")),"Lua registration preserves POST through detached queue");
   LuaManager.env.rawset("result",null);ApiBridgeLifecycle.update();
   check(LuaManager.env.rawget("result")==null,"completion delivered exactly once");
   check(result.rawget("body")==null,"only decoded response exposed");
   AtomicReference<Boolean> offThread=new AtomicReference<>();
   Thread other=new Thread(()->offThread.set(ApocBrApiBridge.isEnabled()));other.start();other.join();
   check(Boolean.FALSE.equals(offThread.get()),"unrelated thread excluded");
   GameServer.server=false;check(!ApocBrApiBridge.isEnabled(),"client excluded");GameServer.server=true;
   ApiBridgeLifecycle.shutdown();check(!ApocBrApiBridge.isEnabled(),"terminal shutdown");
  }finally{install(null);}
 }
 static void startup()throws Exception{
  System.setProperty("apocbr.bridge.api.enabled","false");System.setProperty("apocbr.bridge.serverKey","dummy-fixture-key");
  ApiBridgeLifecycle.initialize();check(!ApocBrApiBridge.isEnabled(),"explicit disabled");
  System.clearProperty("apocbr.bridge.api.enabled");System.setProperty("apocbr.bridge.enabled","false");
  ApiBridgeLifecycle.initialize();check(ApocBrApiBridge.isEnabled(),"default enabled independent from radio");
  ApiBridgeLifecycle.shutdown();install(null);System.clearProperty("apocbr.bridge.serverKey");
  ApiBridgeLifecycle.initialize();check(!ApocBrApiBridge.isEnabled(),"missing credential disables safely");
 }
 static void http()throws Exception{
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
  AtomicReference<String> key=new AtomicReference<>();AtomicInteger redirected=new AtomicInteger();
  AtomicReference<String> method=new AtomicReference<>();AtomicInteger requestBytes=new AtomicInteger(-1);
  server.createContext("/bind",exchange->{method.set(exchange.getRequestMethod());requestBytes.set(exchange.getRequestBody().readAllBytes().length);key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));byte[] body="{\"balance\":15000}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/ok",exchange->{key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));byte[] body="{\"balance\":15000}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
  server.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
  server.createContext("/huge",exchange->{byte[] body=new byte[16385];exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/limited",exchange->{exchange.getResponseHeaders().set("Retry-After","120");exchange.sendResponseHeaders(429,-1);exchange.close();});
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  server.createContext("/blocked",exchange->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}exchange.close();});
  server.createContext("/stalled",exchange->{exchange.sendResponseHeaders(200,1);try{release.await();}catch(InterruptedException ignored){}exchange.close();});
  server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
  try(ApiHttpTransport transport=new ApiHttpTransport("dummy-fixture-key")){
   check(transport.send(URI.create(base+"/ok")).status()==200,"loopback HTTP");
   check("dummy-fixture-key".equals(key.get()),"X-API-Key");
   check(transport.send(URI.create(base+"/bind?sourceServerId=2"),"POST").status()==200,"registration POST reaches loopback backend");
   check("POST".equals(method.get())&&requestBytes.get()==0&&"dummy-fixture-key".equals(key.get()),"registration sends authenticated POST without a caller-controlled body");
   check(transport.send(URI.create(base+"/bind"),"POST","{\"amount\":25}").status()==200&&requestBytes.get()>0,"debit JSON body crosses HTTP transport");
   check("HTTP_302".equals(transport.send(URI.create(base+"/redirect")).error())&&redirected.get()==0,"no redirects");
   check("RESPONSE_TOO_LARGE".equals(transport.send(URI.create(base+"/huge")).error()),"bounded response");
   check(transport.send(URI.create(base+"/limited")).cooldownMillis()==120000,"Retry-After seconds");
   long timeoutStart=System.nanoTime();
   check("TIMEOUT".equals(transport.send(URI.create(base+"/stalled")).error()),"deadline includes stalled response body");
   check(System.nanoTime()-timeoutStart<TimeUnit.SECONDS.toNanos(12),"body timeout bounded to ten seconds");
   ApiDispatcher dispatcher=new ApiDispatcher(new ApiHttpTransport("dummy-fixture-key"),1500,System::nanoTime);
   dispatcher.start();dispatcher.submit("blocked",URI.create(base+"/blocked"));
   check(entered.await(3,TimeUnit.SECONDS),"in flight");long start=System.nanoTime();dispatcher.close();
   check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(200),"shutdown never joins network worker");
   check("STOPPED".equals(dispatcher.submit("late",URI_VALUE)),"shutdown rejects requests");
  }finally{release.countDown();server.stop(0);executor.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{
  parameters();debit();bounded();cooldown();concurrentAdmission();callbacks();startup();http();
  System.out.println("Passed "+checks+" API bridge assertions (42.22.2, real Kahlua + loopback HTTP).");
 }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix='apocbr-api-') as directory:
        work = Path(directory)
        sources = []
        for name, content in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        harness = work / 'ApiBridgeTest.java'
        harness.write_text(HARNESS, encoding='utf-8')
        sources.append(harness)
        sources.extend((ROOT / '42.22.2/src/zombie/apocbr/api').glob('*.java'))
        args = work / 'sources.args'
        args.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
        classes = work / 'classes'
        subprocess.run([str(ROOT / 'jdk/bin/javac.exe'), '--release', '25', '--add-modules', 'jdk.httpserver',
                        '-cp', str(JAR), '-d', str(classes), '@' + str(args)], check=True, timeout=60)
        # Match loose-class deployment without changing the installed signed game JAR.
        unsigned = work / 'game-fixture.jar'
        with zipfile.ZipFile(JAR) as original, zipfile.ZipFile(unsigned, 'w') as target:
            for entry in original.infolist():
                upper = entry.filename.upper()
                if upper.startswith('META-INF/') and upper.endswith(('.SF', '.RSA', '.DSA', '.EC')):
                    continue
                target.writestr(entry, original.read(entry))
        subprocess.run([str(ROOT / 'jdk/bin/java.exe'), '--add-modules', 'jdk.httpserver',
                        '-cp', str(classes) + os.pathsep + str(unsigned), 'zombie.apocbr.api.ApiBridgeTest'],
                       check=True, timeout=60)


if __name__ == '__main__':
    main()

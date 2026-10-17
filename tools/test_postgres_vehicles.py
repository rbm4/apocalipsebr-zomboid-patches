"""Actual PostgreSQL vehicle SQL/store tests with isolated JDBC and engine fixtures."""
from postgres_test_support import inactive_persistence_stubs

from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '42.21.0/src/zombie'
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK = ROOT / 'jdk/bin'
LIB = ROOT / '42.21.0/lib'

HARNESS = r'''
package zombie.savefile;
import java.sql.*;import java.lang.reflect.*;import java.util.*;import javax.sql.DataSource;
public class VehicleDatabaseTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static Object zero(Class<?> t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
 static class Fake {
  Map<Integer,ApocBRVehicleDatabase.Vehicle> rows=new LinkedHashMap<>();
  List<String> sql=new ArrayList<>();int opened,closed,commits,rollbacks; boolean fail;
  Connection open(){opened++;final Runnable[] pending={null};
   return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
    switch(m.getName()){
     case "close":closed++;return null;
     case "setAutoCommit":check(!(boolean)a[0],"vehicle transactions disable autocommit");return null;
     case "setSchema":check(a[0].equals("public"),"schema public");return null;
     case "commit":if(fail)throw new SQLException("password=PRIVATE_SECRET","08006");commits++;if(pending[0]!=null)pending[0].run();pending[0]=null;return null;
     case "rollback":rollbacks++;pending[0]=null;return null;
     case "prepareStatement":return statement((String)a[0],pending);
    }return zero(m.getReturnType());});}
  DataSource datasource(){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
   (p,m,a)->m.getName().equals("getConnection")?open():zero(m.getReturnType()));}
  PreparedStatement statement(String query,Runnable[] pending){sql.add(query);Map<Integer,Object> args=new HashMap<>();
   return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
    if(m.getName().equals("setQueryTimeout")){check(a[0].equals(2),"bounded SQL");return null;}
    if(m.getName().startsWith("set")){args.put((Integer)a[0],a[1]);return null;}
    if(m.getName().equals("executeUpdate")){
     if(query.startsWith("DELETE")){int id=(int)args.get(1);pending[0]=()->rows.remove(id);}
     else {var vehicle=new ApocBRVehicleDatabase.Vehicle((int)args.get(11),(int)args.get(1),(int)args.get(2),
      (float)args.get(3),(float)args.get(4),(int)args.get(5),(boolean)args.get(6),(byte[])args.get(7),ApocBRPayloadProvenance.of(args.get(8),args.get(9),args.get(10)));
      pending[0]=()->rows.put(vehicle.id(),vehicle);}
     return 1;
    }
    if(m.getName().equals("executeQuery")){
     if(fail)throw new SQLException("password=PRIVATE_SECRET","08006");
     List<ApocBRVehicleDatabase.Vehicle> selected=new ArrayList<>();
     if(!query.contains("WHERE false"))for(var row:rows.values()){
      if(query.startsWith("SELECT 1") && row.id()!=(int)args.get(1))continue;
      if(query.contains("wx=?") && (row.wx()!=(int)args.get(1)||row.wy()!=(int)args.get(2)))continue;
      if(query.contains("inmeta=TRUE")&&!row.inMeta())continue;
      selected.add(row);
     }
     int[] index={-1};return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->{
      if(rm.getName().equals("next"))return ++index[0]<selected.size();
      if(rm.getName().startsWith("get")){check(index[0]>=0&&index[0]<selected.size(),"positioned cursor");var row=selected.get(index[0]);int column=(int)ra[0];
       if(query.startsWith("SELECT wx"))return column==1?row.wx():column==2?row.wy():row.id();
       return switch(column){case 1->row.id();case 2->row.wx();case 3->row.wy();case 4->row.x();case 5->row.y();case 6->row.data();case 7->row.worldVersion();case 8->row.inMeta();case 9->row.origin()==null?null:row.origin().serverId();case 10->row.origin()==null?null:row.origin().namespaceId();case 11->row.origin()==null?null:row.origin().viewId();default->throw new AssertionError("column");};
      }return zero(rm.getReturnType());});
    }return zero(m.getReturnType());});}
 }
 public static void main(String[] ignored)throws Exception{
  Fake fake=new Fake();var db=new ApocBRVehicleDatabase(fake::open);
  check(db.initialize().isEmpty(),"empty vehicle database accepted");
  var foreignOrigin=new ApocBRPayloadProvenance(2,UUID.randomUUID(),UUID.randomUUID());
  byte[] bytes={1,2,3};var snapshot=new ApocBRVehicleDatabase.Vehicle(17,4,5,32.5f,40.5f,249,false,bytes,foreignOrigin);bytes[0]=99;
  db.save(snapshot);check(fake.rows.size()==1 && fake.rows.get(17).data()[0]==1,"insert preserves native ID and bytes");
  check(db.loadChunk(4,5).getFirst().origin().equals(foreignOrigin),"foreign origin survives SQL read without local relabel");
  db.save(db.loadChunk(4,5).getFirst());check(fake.rows.get(17).origin().equals(foreignOrigin),"unchanged copied vehicle retains its exact encoding view");
  check(fake.sql.stream().anyMatch(q->q.startsWith("INSERT")),"initial insert path");
  db.save(new ApocBRVehicleDatabase.Vehicle(17,8,9,64.5f,72.5f,249,true,new byte[]{4,5}));
  check(fake.rows.size()==1 && fake.rows.get(17).wx()==8,"existing vehicle moves chunks without another ID");
  check(fake.sql.stream().anyMatch(q->q.startsWith("UPDATE")),"existing update path");
  check(db.loadChunk(4,5).isEmpty(),"old chunk empty after move");
  var loaded=db.loadChunk(8,9);check(loaded.getFirst().origin()==null,"new unattributed bytes clear previous tuple");check(loaded.size()==1&&loaded.get(0).data()[0]==4,"new chunk returns saved payload");
  check(fake.opened==fake.closed,"read connection released before callback/integration");
  check(db.loadMeta().size()==1,"meta flag filter");
  check(db.initialize().get(0).id()==17,"startup IDs imported");
  fake.fail=true;
  try{db.save(snapshot);throw new AssertionError("expected failure");}catch(SQLException e){check(!e.toString().contains("PRIVATE_SECRET"),"safe write error");}
  check(fake.rollbacks==1&&fake.rows.get(17).wx()==8,"write rollback retains prior vehicle");
  try{db.loadChunk(8,9);throw new AssertionError("load failure returned empty success");}catch(SQLException expected){}
  fake.fail=false;db.remove(17);check(fake.rows.isEmpty(),"delete persisted");
  int before=fake.opened;db.remove(-1);check(fake.opened==before,"invalid remove is no-op");
  try{db.save(new ApocBRVehicleDatabase.Vehicle(0,0,0,0,0,249,false,new byte[]{1}));throw new AssertionError("bad ID accepted");}catch(SQLException expected){}
  check(fake.opened==fake.closed,"all leases returned including failures");

  // Exercise the actual VehiclesDB2 store selection, startup and native buffer adapter.
  var connector=new ApocBRPostgresConnector(fake.datasource(),s->{});
  Field instance=ApocBRPostgresConnector.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,connector);
  Field configured=ApocBRPostgresConnector.class.getDeclaredField("configured");configured.setAccessible(true);configured.setBoolean(null,true);
  System.setProperty("apocbr.postgres.enabled","true");
  System.clearProperty("apocbr.postgres.vehicles.enabled");
  check(!ApocBRVehicleDatabase.enabled(),"master enablement retains native SQLite vehicles by default");
  System.setProperty("apocbr.postgres.vehicles.enabled","false");
  check(!ApocBRVehicleDatabase.enabled(),"explicit false retains SQLite vehicles");
  System.setProperty("apocbr.postgres.vehicles.enabled","true");
  var engine=zombie.vehicles.VehiclesDB2.instance;engine.init();
  Field ws=engine.getClass().getDeclaredField("worldStreamer");ws.setAccessible(true);Object worker=ws.get(engine);
  Field storeField=worker.getClass().getDeclaredField("store");storeField.setAccessible(true);Object store=storeField.get(worker);
  check(store.getClass().getSimpleName().equals("PostgresStore"),"dedicated server selects PostgreSQL store");
  Class<?> bufferType=Class.forName("zombie.vehicles.VehiclesDB2$VehicleBuffer");
  Constructor<?> ctor=bufferType.getDeclaredConstructor();ctor.setAccessible(true);Object buffer=ctor.newInstance();
  for(String name:new String[]{"id","wx","wy","worldVersion"}){Field f=bufferType.getDeclaredField(name);f.setAccessible(true);f.setInt(buffer,name.equals("id")?23:name.equals("worldVersion")?249:4);}
  Field bb=bufferType.getDeclaredField("bb");bb.setAccessible(true);java.nio.ByteBuffer payload=java.nio.ByteBuffer.wrap(new byte[]{1,7,9});payload.position(1);bb.set(buffer,payload);
  Field originField=bufferType.getDeclaredField("origin");originField.setAccessible(true);originField.set(buffer,foreignOrigin);
  Method update=store.getClass().getDeclaredMethod("updateVehicle",bufferType);update.setAccessible(true);update.invoke(store,buffer);
  check(fake.rows.get(23).data()[0]==1 && payload.position()==1,"native adapter persists bytes without mutating buffer position");
  check(fake.rows.get(23).origin().equals(foreignOrigin),"native worker adapter uses buffered origin");
  Method remove=store.getClass().getDeclaredMethod("removeVehicle",int.class);remove.setAccessible(true);remove.invoke(store,23);
  check(fake.rows.isEmpty(),"native adapter removes PostgreSQL vehicle");
  connector.close();try{db.initialize();}catch(Exception unexpected){throw new AssertionError("independent fake factory",unexpected);}
  System.out.println("Vehicles: SQL insert/update/load/meta/delete, startup, rollback, safe errors and actual native adapter passed");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='apocbr_vehicle_test_') as folder:
    target = Path(folder)
    stubs = {
        'zombie/core/Core.java': 'package zombie.core; public class Core { public static boolean debug; private static final Core core=new Core(); public static Core getInstance(){return core;} public boolean isNoSave(){return false;} }',
        'zombie/network/GameServer.java': 'package zombie.network; public class GameServer { public static boolean server=true; }',
    }
    files=[]
    for name, body in stubs.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8');files.append(path)
    harness=target/'VehicleDatabaseTest.java';harness.write_text(HARNESS,encoding='utf-8');files.append(harness)
    files += [SRC/'savefile/ApocBRPayloadProvenance.java', SRC/'savefile/ApocBRVehicleDatabase.java', SRC/'savefile/ApocBRPostgresConnector.java', SRC/'vehicles/VehiclesDB2.java']
    cp=os.pathsep.join(map(str,[GAME,LIB/'postgresql-42.7.14.jar',LIB/'HikariCP-7.1.0.jar']))
    files += inactive_persistence_stubs(target, mutations=True)
    subprocess.run([str(JDK/'javac.exe'),'--release','25','-cp',cp,'-d',folder,*map(str,files)],check=True)
    subprocess.run([str(JDK/'java.exe'),'-cp',folder+os.pathsep+cp,'zombie.savefile.VehicleDatabaseTest'],check=True)

source=(SRC/'vehicles/VehiclesDB2.java').read_text(encoding='utf-8')
assert 'GameServer.server && ApocBRVehicleDatabase.enabled()' in source
assert 'chunk.loadVehiclesObject != this' in source
shutdown=(SRC/'network/ServerMap.java').read_text(encoding='utf-8')
assert shutdown.index('VehiclesDB2.instance.Reset();') < shutdown.index('ApocBRPostgresConnector.closePool();')
print('Native load completion guards and shutdown ordering checked')

"""Run exact 42.21.0 vanilla/patched geometry with a deterministic triangulator boundary."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / '42.21.0-client'
FILES = {
    'zombie/core/math/PZMath.java': '''package zombie.core.math;public class PZMath {
 public static int min(int a,int b){return Math.min(a,b);}public static int max(int a,int b){return Math.max(a,b);}
 public static int roundToInt(float f){return Math.round(f);}}''',
    'zombie/iso/MapFiles.java': '''package zombie.iso;public class MapFiles {
 public static java.util.ArrayList<MapFiles> getCurrentMapFiles(){return new java.util.ArrayList<>();}
 public boolean hasCell300(int x,int y){return false;}}''',
    'zombie/vehicles/Clipper.java': '''package zombie.vehicles;public class Clipper {
 public void clear(){}public void addPath(int n,java.nio.ByteBuffer b,boolean hole){}public void clipAABB(float a,float b,float c,float d){}
 public int generatePolygons(double delta){return 1;}
 public int triangulate2(int polygon,java.nio.ByteBuffer b){
 b.putShort((short)4);for(int i=0;i<4;i++){b.putFloat(i);b.putFloat(i+1);}
 for(int i:new int[]{0,1,2,0,2,3})b.putShort((short)i);b.flip();return 6;}}''',
    'zombie/worldMap/WorldMapPoints.java': '''package zombie.worldMap;public class WorldMapPoints {
 public int minX,minY,maxX=3,maxY=4;public void calculateBounds(){}public int numPoints(){return 4;}
 public int getX(int i){return i;}public int getY(int i){return i+1;}public boolean isClockwise(){return false;}}''',
    'zombie/worldMap/WorldMapCell.java': '''package zombie.worldMap;public class WorldMapCell {
 public int x,y,priority;public int[] clipHigherPriorityCells=new int[0];
 public final java.nio.ShortBuffer indexBuffer=java.nio.ByteBuffer.allocateDirect(600000).asShortBuffer();
 public final java.nio.FloatBuffer triangleBuffer=java.nio.ByteBuffer.allocateDirect(600000).asFloatBuffer();
 public java.nio.ShortBuffer getIndexBuffer(int n){return indexBuffer;}
 public java.nio.FloatBuffer getTriangleBuffer(int n){return triangleBuffer;}}''',
    'zombie/worldMap/GeometryTest.java': '''package zombie.worldMap;public class GeometryTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void one(int offset,boolean patched,boolean zoom){
  var cell=new WorldMapCell();cell.indexBuffer.position(offset);
  var g=new WorldMapGeometry(cell);g.type=WorldMapGeometry.Type.Polygon;g.points.add(new WorldMapPoints());
  g.calculateBounds();g.triangulate(cell,zoom?new double[]{1,2,4,8,12,18}:null);
  check(g.indexCount==6,"triangle count preserved");
  check(g.firstIndex==(patched?offset:(short)offset),"native offset differs only at signed-short narrowing");
  if(patched||offset<32768){int[] expected={0,1,2,0,2,3};for(int j=0;j<6;j++)check((cell.indexBuffer.get(g.firstIndex+j)&65535)==expected[j],"renderer reads the exact intended indices");}
  else if(offset<65536){try{cell.indexBuffer.get(g.firstIndex);throw new AssertionError("vanilla overflow not reproduced");}catch(IndexOutOfBoundsException expected){checks++;}}
  else check(g.firstIndex!=offset,"vanilla silently wraps larger offsets");
  if(zoom){for(double delta:new double[]{1,2,4,8,12,18}){var variant=g.findTriangles(delta);check(variant!=null&&variant.firstIndex>offset&&variant.indexCount==5,"zoom variants preserved");}}
  g.clearTriangles();check(g.firstIndex==-1&&g.indexCount==-1&&g.trianglesPerZoom==null,"clear/retriangulate sentinel preserved");
 }
 public static void main(String[] args){boolean patched=Boolean.parseBoolean(args[0]);
  for(int offset:new int[]{0,32765,32766,32767,32768,40000,65534,65535,65536,100000,200000}){
   one(offset,patched,false);one(offset,patched,true);
  }
  System.out.println((patched?"Patched":"Vanilla")+" geometry: "+checks+" checks; "+(patched?"large cell offsets and zoom variants preserved":"signed-short overflow reproduced"));
 }}''',
}

with tempfile.TemporaryDirectory(prefix='apocbr_worldmap_indices_') as folder:
    base = Path(folder)
    for variant, source in [('vanilla', TARGET / 'decompiled/zombie/worldMap/WorldMapGeometry.java'),
                            ('patched', TARGET / 'src/zombie/worldMap/WorldMapGeometry.java')]:
        target = base / variant
        files = []
        for name, body in FILES.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            files.append(path)
        files.append(source)
        subprocess.run([str(ROOT / 'jdk/bin/javac.exe'), '--release', '25', '-d', str(target), *map(str, files)], check=True)
        subprocess.run([str(ROOT / 'jdk/bin/java.exe'), '-cp', str(target), 'zombie.worldMap.GeometryTest', str(variant == 'patched').lower()], check=True, timeout=15)

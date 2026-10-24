package zombie.apocalipse.humans;

import java.util.IdentityHashMap;
import zombie.apocalipse.humans.tasks.HumanCorpsePort;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;
import zombie.network.id.ObjectIDManager;

/** Bodies remain in the world throughout hauling. No inventory conversion, cloning or grapple resurrection. */
final class ServerHumanCorpses implements HumanCorpsePort {
    private static final IdentityHashMap<IsoDeadBody,ServerHumanCorpses> claims=new IdentityHashMap<>();
    private final ServerHuman human;
    private IsoDeadBody observed;
    private long sequence,token;
    private int scanTile,scanBody;
    private boolean scanUnloaded;
    private Point searchCenter,searchDestination;
    private int searchRadius;
    private int scannedObjects,scannedCorpses,invalidCorpses,outsideScope,atDropOff,claimedElsewhere;
    private void resetScan(){scanTile=scanBody=0;scanUnloaded=false;scannedObjects=scannedCorpses=invalidCorpses=outsideScope=atDropOff=claimedElsewhere=0;}
    private void reportScan(String result,Body selected){
        HumanLog.log(HumanLog.Level.DEBUG,()->"corpse search key="+human.definition.key()+"; source="+searchCenter
            +"; radius="+searchRadius+"; destination="+searchDestination+"; result="+result+"; selected="+selected
            +"; objects="+scannedObjects+"; corpses="+scannedCorpses+"; invalid="+invalidCorpses
            +"; outsideScope="+outsideScope+"; atDropOff="+atDropOff+"; claimedElsewhere="+claimedElsewhere+"; loaded="+!scanUnloaded);
    }
    ServerHumanCorpses(ServerHuman human){this.human=human;}
    public String movementSlot(){return human.profile.walkingPatterns().stream().anyMatch(p->p.slot().equals("corpse-cleanup"))?"corpse-cleanup":"background";}
    public boolean permitted(Point p){
        if(p==null||p.z()!=human.player.getZi())return false;
        var scope=human.walking.scope(movementSlot());
        return scope!=null&&scope.walkable(new Tile(p.x(),p.y()));
    }
    private boolean valid(IsoDeadBody body){
        return body!=null&&!body.isAnimal()&&!body.isFakeDead()&&body.getSquare()!=null
            &&body.getStaticMovingObjectIndex()>=0&&ObjectIDManager.get(body.getObjectID())==body;
    }
    private Point position(IsoDeadBody body){return new Point(body.getXi(),body.getYi(),body.getZi());}
    public Search find(Point center,int radius,Point destination){
        if(radius<0||radius>2)throw new IllegalArgumentException("Invalid corpse search radius");
        if(observed!=null&&claims.get(observed)==this&&valid(observed))return new Search(new Body(token,position(observed)),false,true);
        if(!center.equals(searchCenter)||!destination.equals(searchDestination)||radius!=searchRadius){
            searchCenter=center;searchDestination=destination;searchRadius=radius;resetScan();
        }
        int width=2*radius+1,total=width*width,visited=0;
        while(scanTile<total&&visited<128){
            long x=(long)center.x()+scanTile/width-radius,y=(long)center.y()+scanTile%width-radius;
            var square=x<0||y<0||x>=Integer.MAX_VALUE||y>=Integer.MAX_VALUE?null:human.player.getCell().getGridSquare((int)x,(int)y,center.z());
            if(square==null){scanUnloaded=true;scanTile++;scanBody=0;continue;}
            var objects=square.getStaticMovingObjects();
            while(scanBody<objects.size()&&visited<128){
                visited++;
                var object=objects.get(scanBody++);
                scannedObjects++;
                if(object instanceof IsoDeadBody body){
                    scannedCorpses++;
                    if(!valid(body)){invalidCorpses++;continue;}
                    if(position(body).equals(destination)){atDropOff++;continue;}
                    if(!permitted(position(body))){outsideScope++;continue;}
                    if(claims.containsKey(body)&&claims.get(body)!=this){claimedElsewhere++;continue;}
                    observed=body;token=++sequence;var selected=new Body(token,position(body));
                    reportScan("selected",selected);resetScan();
                    return new Search(selected,false,true);
                }
            }
            if(scanBody>=objects.size()){scanTile++;scanBody=0;}
        }
        boolean complete=scanTile>=total,loaded=!scanUnloaded;
        if(complete){reportScan("complete",null);resetScan();}
        return new Search(null,complete,loaded);
    }
    public Body resolve(long id){return id==token&&valid(observed)?new Body(id,position(observed)):null;}
    public boolean claim(long id){
        if(resolve(id)==null)return false;
        var owner=claims.get(observed);if(owner!=null&&owner!=this)return false;
        claims.put(observed,this);return true;
    }
    public boolean move(long id,Point point){
        if(resolve(id)==null||claims.get(observed)!=this||!permitted(point)
            ||point.x()!=human.player.getXi()||point.y()!=human.player.getYi())return false;
        var from=observed.getSquare();var to=human.player.getCell().getGridSquare(point.x(),point.y(),point.z());
        if(to==null)return false;
        if(position(observed).equals(point))return true;
        // The executor calls this after every physical route step; never cross more than one tile.
        if(Math.abs((long)point.x()-observed.getXi())+Math.abs((long)point.y()-observed.getYi())!=1
            ||human.movement.traversalCost(new Tile(observed.getXi(),observed.getYi()),new Tile(point.x(),point.y()))
                !=zombie.apocalipse.humans.traversal.TraversalEdge.Kind.WALK.cost())return false;
        from.removeCorpse(observed,false);
        observed.setX(point.x()+.5f);observed.setY(point.y()+.5f);observed.setZ(point.z());observed.setSquare(to);
        to.addCorpse(observed,true);GameServer.sendCorpse(observed);
        return true;
    }
    public void release(long id){if(id==token&&claims.get(observed)==this)claims.remove(observed);}
}

package zombie.apocalipse.humans;

import zombie.characters.IsoGameCharacter;
import zombie.iso.LosUtil;
import zombie.pathfind.PolygonalMap2;

/** Simulation-thread weapon obstruction queries, shared by placement, windup and hit commitment. */
final class ServerHumanAttackLine {
    private ServerHumanAttackLine(){ }
    static boolean clear(ServerHuman human,IsoGameCharacter target,float x,float y,boolean ranged){
        int z=human.player.getZi();
        if(!Float.isFinite(x)||!Float.isFinite(y)||target.getZi()!=z||target.getCurrentSquare()==null)return false;
        if(ranged){
            int fromX=(int)Math.floor(x),fromY=(int)Math.floor(y),toX=target.getXi(),toY=target.getYi();
            int steps=Math.max(Math.abs(toX-fromX),Math.abs(toY-fromY));
            if(steps>32)return false;
            // Native LOS can skip unloaded squares; do not authorize a shot into an unknown segment.
            for(int n=0;n<=steps;n++){
                int sx=steps==0?fromX:fromX+Math.round((toX-fromX)*(float)n/steps);
                int sy=steps==0?fromY:fromY+Math.round((toY-fromY)*(float)n/steps);
                if(human.player.getCell().getGridSquare(sx,sy,z)==null)return false;
            }
            // Match 42.21.0 CombatManager's visibility-based target obstruction rule.
            // Furniture that stops walking need not stop a shot; walls and closed doors still do.
            var result=LosUtil.lineClear(human.player.getCell(),fromX,fromY,z,toX,toY,z,false);
            if(result==LosUtil.TestResults.Blocked||result==LosUtil.TestResults.ClearThroughClosedDoor)return false;
        }else if(PolygonalMap2.instance.lineClearCollide(x,y,target.getX(),target.getY(),z,null,false,true))return false;
        return ServerHumanCombatNetwork.clearBodies(human,target,x,y);
    }
}

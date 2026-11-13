package zombie.apocalipse.humans.furniture;

import java.util.*;

/** Server-owned fair capture ordering. Every participant shares one bounded pass budget. */
public final class FurnitureCaptureScheduler {
    public interface Participant { void capture(long now,FurnitureScanBudget budget); }
    private final Thread owner=Thread.currentThread();
    private final List<Participant> participants=new ArrayList<>();
    private final FurnitureScanBudget budget=new FurnitureScanBudget();
    private int cursor;
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Furniture scheduling requires server owner");}
    public void add(Participant participant){
        check();Objects.requireNonNull(participant);
        if(participants.stream().anyMatch(p->p==participant))throw new IllegalArgumentException("Duplicate furniture observer");
        if(participants.size()==64)throw new IllegalStateException("Too many furniture observers");participants.add(participant);
    }
    public void remove(Participant participant){check();participants.removeIf(p->p==participant);if(participants.isEmpty())cursor=0;else cursor%=participants.size();}
    public void capture(long now){
        check();if(participants.isEmpty())return;
        var snapshot=List.copyOf(participants);int size=snapshot.size(),start=Math.floorMod(cursor,size),next=(start+1)%size;
        budget.reset();
        for(int n=0;n<size;n++){
            int index=(start+n)%size;var participant=snapshot.get(index);
            if(participants.stream().noneMatch(p->p==participant))continue;
            int squares=budget.usedSquares(),objects=budget.usedObjects();
            participant.capture(now,budget);
            if(squares!=budget.usedSquares()||objects!=budget.usedObjects())next=(index+1)%size;
        }
        // Resume after the last observer that actually consumed work, not after an empty scan.
        if(!participants.isEmpty()){
            var successor=snapshot.get(next);int found=-1;
            for(int i=0;i<participants.size();i++)if(participants.get(i)==successor){found=i;break;}
            cursor=found<0?Math.floorMod(next,participants.size()):found;
        }
    }
    public int size(){check();return participants.size();}
}

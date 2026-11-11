package zombie.apocalipse.humans.furniture;

/** Shared authoritative-pass work budget. No policy or native dependencies. */
public final class FurnitureScanBudget {
    private final Thread owner=Thread.currentThread();
    private int squares,objects;
    public void reset(){check();squares=256;objects=512;}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Furniture scan requires server owner");}
    public boolean square(){check();if(squares<=0)return false;squares--;return true;}
    public boolean object(){check();if(objects<=0)return false;objects--;return true;}
}

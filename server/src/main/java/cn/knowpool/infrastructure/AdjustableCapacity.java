package cn.knowpool.infrastructure;

import java.util.function.IntSupplier;

/** 调低容量只限制后续领取，已接收活动不会被取消。 */
public final class AdjustableCapacity {
    private final IntSupplier limit;
    private int used;
    public AdjustableCapacity(IntSupplier limit) { this.limit=limit; }
    public synchronized boolean tryAcquire() { if (used>=limit.getAsInt()) return false;used++;return true; }
    public synchronized void acquire() throws InterruptedException { while (!tryAcquire()) wait(50); }
    public void acquireUninterruptibly() { boolean interrupted=false;while (true) try { acquire();break; } catch (InterruptedException failure) { interrupted=true; }if (interrupted) Thread.currentThread().interrupt(); }
    public synchronized void release() { if (used>0) used--;notifyAll(); }
}

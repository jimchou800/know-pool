package cn.knowpool.infrastructure;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 维护期间拒绝新业务，并等待已取得租约的完整活动结束。 */
public final class MaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean paused;
    public boolean paused() { return paused; }
    public synchronized boolean pause() { if (paused) return false; paused=true; return true; }
    public void resume() { paused=false; }
    public Lease enter() {
        if (paused) return null;
        lock.readLock().lock();
        if (paused) { lock.readLock().unlock();return null; }
        return () -> lock.readLock().unlock();
    }
    public Lease exclusive() { lock.writeLock().lock();return () -> lock.writeLock().unlock(); }
    public Runnable activity(Runnable action) {
        return () -> {
            while (true) {
                lock.readLock().lock();
                if (!paused) break;
                lock.readLock().unlock();
                if (Thread.currentThread().isInterrupted()) return;
                java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
            }
            try { action.run(); } finally { lock.readLock().unlock(); }
        };
    }
    public interface Lease extends AutoCloseable { @Override void close(); }
}

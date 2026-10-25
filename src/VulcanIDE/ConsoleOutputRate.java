package VulcanIDE;

/** Throttles program console output after the startup burst to the configured rate. */
final class ConsoleOutputRate {
    private volatile int kibPerSecond=256;
    void setKiBPerSecond(int value){kibPerSecond=Math.max(0,value);}
    private int lines;
    private int batch;
    private long nextBatch;
    synchronized void reset() { lines = 0; batch = 0; nextBatch = 0; }
    void accept(int value) {
        long pause = 0;
        synchronized (this) {
            if (lines < 100) { if (value == '\n') lines++; return; }
            if (kibPerSecond==0) return;
            if (++batch < 1024) return;
            batch = 0;
            long now = System.nanoTime();
            long interval=1_000_000_000L/kibPerSecond;
            nextBatch = Math.max(now, nextBatch) + interval;
            pause = Math.min(interval, nextBatch - now);
        }
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) java.util.concurrent.locks.LockSupport.parkNanos(pause);
    }
    synchronized boolean startup() { return lines < 100; }
}

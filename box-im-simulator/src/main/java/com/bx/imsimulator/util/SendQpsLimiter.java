package com.bx.imsimulator.util;

/**
 * 全局限流器：多线程共享，保证平均发送速率不超过指定 QPS。
 */
public final class SendQpsLimiter {

    private final long intervalNanos;
    private long nextPermitNanos;

    public SendQpsLimiter(int maxQps) {
        if (maxQps < 1) {
            throw new IllegalArgumentException("maxQps must be >= 1");
        }
        this.intervalNanos = 1_000_000_000L / maxQps;
        this.nextPermitNanos = System.nanoTime();
    }

    public void acquire() throws InterruptedException {
        long sleepNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long permitAt = Math.max(now, nextPermitNanos);
            nextPermitNanos = permitAt + intervalNanos;
            sleepNanos = permitAt - now;
        }
        if (sleepNanos > 0) {
            long ms = sleepNanos / 1_000_000L;
            int ns = (int) (sleepNanos % 1_000_000L);
            Thread.sleep(ms, ns);
        }
    }
}

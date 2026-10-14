package com.bx.imsimulator.job;

import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 私聊模拟单任务状态（内存），由 {@code GET .../status} 轮询读取。
 */
@Data
public class PrivateMsgSimulateJob {

    public enum Status {
        RUNNING,
        SETTLING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private volatile long createdAtMs = System.currentTimeMillis();
    private volatile long finishedAtMs;
    private volatile long httpAllFinishedAtMs;
    private volatile Status  status = Status.RUNNING;
    private volatile String phase = "queued";
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile int senderCount;
    private volatile int receiverCount;
    private volatile int sendRounds;
    /** 0 表示不限制 */
    private volatile int maxSendQps;
    private volatile int totalRequested;
    private volatile Long wsPrivateMessagesReceived;
    private volatile String message = "running";
    private final List<Map<String, Object>> failureSamples = new ArrayList<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);







    public void incrementSucceeded() {
        succeeded.incrementAndGet();
    }

    public void incrementFailed() {
        failed.incrementAndGet();
    }


    public void addFailureSample(Map<String, Object> row) {
        synchronized (failureSamples) {
            if (failureSamples.size() < 10) {
                failureSamples.add(row);
            }
        }
    }

    public List<Map<String, Object>> getFailureSamplesView() {
        synchronized (failureSamples) {
            return List.copyOf(failureSamples);
        }
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public void markFailed(String msg) {
        if (isFinished()) {
            return;
        }
        setMessage(msg);
        setStatus(Status.FAILED);
        setFinishedAtMs(System.currentTimeMillis());
    }

    public void markCompleted(String msg, long wsDelta) {
        if (isFinished()) {
            return;
        }
        setWsPrivateMessagesReceived(wsDelta);
        setMessage(msg);
        setStatus(Status.COMPLETED);
        setFinishedAtMs(System.currentTimeMillis());
    }

    public void markCancelled(String msg, long wsDelta) {
        if (isFinished()) {
            return;
        }
        setWsPrivateMessagesReceived(wsDelta);
        setMessage(msg);
        setStatus(Status.CANCELLED);
        setFinishedAtMs(System.currentTimeMillis());
    }
}

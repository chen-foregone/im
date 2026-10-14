package com.bx.implatform.perf;

import com.bx.imcommon.perf.PerfCounters;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 与 im-server 侧同构的探针落盘器，写入<strong>另一个</strong>文件。
 *
 * <p>两个进程各有独立 JVM，PerfCounters 是各自的静态副本；
 * 但若共用一个输出文件会互相截断，所以文件名必须分开。</p>
 */
@Slf4j
@Component
public class FanoutPerfCountDumper {

    @Value("${im.perf.probe-enabled:false}")
    private boolean probeEnabled;

    @Value("${im.perf.probe-file:log/perf-fanout.csv}")
    private String probeFile;

    private Path path;
    private double[] prev;
    private long lastAt;

    @PostConstruct
    public void init() {
        PerfCounters.enabled = probeEnabled;
        if (!probeEnabled) {
            return;
        }
        path = Paths.get(probeFile).toAbsolutePath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, PerfCounters.header() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("扇出探针文件初始化失败: {}", path, e);
            return;
        }
        prev = PerfCounters.snapshot();
        lastAt = System.currentTimeMillis();
        log.info("扇出探针已开启，写入: {}", path);
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        if (!probeEnabled || path == null) {
            return;
        }
        double[] now = PerfCounters.snapshot();
        long t = System.currentTimeMillis();
        String line = PerfCounters.diffLine(prev, now, t - lastAt);
        prev = now;
        lastAt = t;
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            log.error("扇出探针写盘失败", e);
        }
    }
}

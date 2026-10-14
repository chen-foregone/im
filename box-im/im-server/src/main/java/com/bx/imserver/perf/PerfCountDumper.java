package com.bx.imserver.perf;

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
 * 每 500ms 把 {@link PerfCounters} 的增量写一行 CSV，便于压测期间外部观察。
 *
 * <p>选择落盘而不是暴露 HTTP 端点，是因为 im-server 没有 Web 容器
 * （只有 8878 的 WebSocket 服务），加 actuator 会引入新依赖。</p>
 */
@Slf4j
@Component
public class PerfCountDumper {

    @Value("${im.perf.probe-enabled:false}")
    private boolean probeEnabled;

    @Value("${im.perf.probe-file:log/perf-counts.csv}")
    private String probeFile;

    private Path path;
    private double[] prev;
    private long lastAt;

    @PostConstruct
    public void init() {
        PerfCounters.enabled = probeEnabled;
        if (!probeEnabled) {
            log.info("性能探针未开启（im.perf.probe-enabled=false）");
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
            log.error("性能探针文件初始化失败: {}", path, e);
            return;
        }
        prev = PerfCounters.snapshot();
        lastAt = System.currentTimeMillis();
        log.info("性能探针已开启，写入: {}", path);
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
            log.error("性能探针写盘失败", e);
        }
    }
}

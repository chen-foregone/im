import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;

public class MapperBench {
    public static class SendInfo {
        public Integer cmd;
        public Object data;
        public SendInfo() {}
    }

    static SendInfo build() {
        SendInfo si = new SendInfo();
        si.cmd = 4;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 1234567890123L);
        data.put("senderId", 1001L);
        data.put("groupId", 1111L);
        data.put("content", "perf-test-content-abcdefghijklmnopqrstuvwxyz");
        data.put("seqNo", 98765L);
        data.put("type", 0);
        data.put("sendTime", System.currentTimeMillis());
        si.data = data;
        return si;
    }

    static long benchNew(int n, SendInfo si) throws Exception {
        for (int i = 0; i < 2000; i++) { new ObjectMapper().writeValueAsString(si); }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { new ObjectMapper().writeValueAsString(si); }
        return System.nanoTime() - t0;
    }

    static long benchShared(int n, SendInfo si, ObjectMapper m) throws Exception {
        for (int i = 0; i < 2000; i++) { m.writeValueAsString(si); }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { m.writeValueAsString(si); }
        return System.nanoTime() - t0;
    }

    public static void main(String[] args) throws Exception {
        int n = 20000;
        SendInfo si = build();
        ObjectMapper shared = new ObjectMapper();

        // 单线程
        long a = benchNew(n, si);
        long b = benchShared(n, si, shared);
        // 再跑一遍，消除 JIT 顺序偏差
        long b2 = benchShared(n, si, shared);
        long a2 = benchNew(n, si);

        double perNew = (a + a2) / 2.0 / n / 1000.0;
        double perShared = (b + b2) / 2.0 / n / 1000.0;
        System.out.printf("单线程 每帧: new=%8.2f us   shared=%8.2f us   倍数=%.1fx%n", perNew, perShared, perNew / perShared);

        // 4 线程（模拟多个 Netty event loop 并发编码）
        int threads = 4, per = 5000;
        for (boolean useNew : new boolean[]{true, false}) {
            ExecutorService es = Executors.newFixedThreadPool(threads);
            List<Future<Long>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final boolean un = useNew;
                fs.add(es.submit(() -> un ? benchNew(per, si) : benchShared(per, si, shared)));
            }
            long total = 0;
            for (Future<Long> f : fs) total += f.get();
            es.shutdown();
            double perFrame = total / (double) threads / per / 1000.0;
            System.out.printf("%d线程并发 每帧: %-6s = %8.2f us   -> 每秒帧数=%9.0f%n",
                threads, useNew ? "new" : "shared", perFrame, 1_000_000.0 / perFrame);
        }

        System.out.println();
        System.out.println("=== 外推到万人群 99,000 帧（单线程顺序编码下的上限估计）===");
        System.out.printf("  new ObjectMapper(): %8.2f s%n", 99000 * perNew / 1_000_000.0);
        System.out.printf("  共享 mapper      : %8.2f s%n", 99000 * perShared / 1_000_000.0);
        System.out.printf("  可回收           : %8.2f s%n", 99000 * (perNew - perShared) / 1_000_000.0);
    }
}

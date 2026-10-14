package com.bx.imcommon.mq;

import com.alibaba.fastjson.JSONObject;
import com.bx.imcommon.perf.PerfCounters;
import com.bx.imcommon.util.ThreadPoolExecutorFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * reids 队列拉取定时任务
 *
 * @author: Blue
 * @date: 2024-07-15
 * @version: 1.0
 */
@Slf4j
@Component
public class RedisMQPullTask implements CommandLineRunner {

    private static final ScheduledThreadPoolExecutor EXECUTOR = ThreadPoolExecutorFactory.getThreadPoolExecutor();

    @Autowired(required = false)
    private List<RedisMQConsumer> consumers = Collections.emptyList();

    @Autowired
    private RedisMQTemplate redisMQTemplate;

    @Override
    public void run(String... args) {
        consumers.forEach((consumer -> {
            // 注解参数
            RedisMQListener annotation = consumer.getClass().getAnnotation(RedisMQListener.class);
            String queue = annotation.queue();
            int batchSize = annotation.batchSize();
            int period = annotation.period();
            // 获取泛型类型
            Type superClass = consumer.getClass().getGenericSuperclass();
            Type type = ((ParameterizedType)superClass).getActualTypeArguments()[0];
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    List<Object> datas = new LinkedList<>();
                    try {
                        if (consumer.isReady()) {
                            String key = consumer.generateKey();
                            final boolean probe = PerfCounters.enabled;
                            // 群队列单独计：它才是压测关注的对象，别和私聊/系统/强制下线混算
                            final boolean isGroupQueue = probe && "im:message:group".equals(queue);
                            long tp0 = probe ? System.nanoTime() : 0L;
                            // 拉取一个批次的数据
                            List<Object> objects = pullBatch(key, batchSize);
                            if (probe) {
                                long dt = System.nanoTime() - tp0;
                                PerfCounters.pullCalls.increment();
                                PerfCounters.pullBatches.increment();
                                PerfCounters.pullBatchNanos.add(dt);
                                boolean empty = objects.isEmpty();
                                if (empty) {
                                    PerfCounters.pullEmptyBatches.increment();
                                }
                                if (isGroupQueue) {
                                    PerfCounters.grpBatches.increment();
                                    PerfCounters.grpBatchNanos.add(dt);
                                    if (empty) {
                                        PerfCounters.grpEmptyBatches.increment();
                                    }
                                }
                            }
                            for (Object obj : objects) {
                                if (obj instanceof JSONObject) {
                                    JSONObject jsonObject = (JSONObject)obj;
                                    long td0 = probe ? System.nanoTime() : 0L;
                                    Object data = jsonObject.toJavaObject(type);
                                    if (probe) {
                                        long dd = System.nanoTime() - td0;
                                        PerfCounters.deserNanos.add(dd);
                                        PerfCounters.pullMessages.increment();
                                        if (isGroupQueue) {
                                            PerfCounters.grpDeserNanos.add(dd);
                                            PerfCounters.grpMsgs.increment();
                                        }
                                    }
                                    consumer.onMessage(data);
                                    datas.add(data);
                                }
                            }
                            if (!datas.isEmpty()) {
                                consumer.onMessage(datas);
                            }
                        }
                    } catch (Exception e) {
                        log.error("数据消费异常,队列:{}", queue, e);
                        // 出现异常，10s后再重新尝试消费
                        EXECUTOR.schedule(this, 10, TimeUnit.SECONDS);
                        return;
                    }
                    // 继续消费数据
                    if (!EXECUTOR.isShutdown()) {
                        if (datas.size() < batchSize) {
                            // 数据已经消费完，等待下一个周期继续拉取
                            EXECUTOR.schedule(this, period, TimeUnit.MILLISECONDS);
                        } else {
                            // 数据没有消费完，直接开启下一个消费周期
                            EXECUTOR.execute(this);
                        }
                    }
                }
            });
        }));
    }

    private List<Object> pullBatch(String key, Integer batchSize) {
        List<Object> objects = new LinkedList<>();
        if (redisMQTemplate.isSupportBatchPull()) {
            // 版本大于6.2，支持批量拉取
            objects = redisMQTemplate.opsForList().leftPop(key, batchSize);
        } else {
            // 版本小于6.2，只能逐条拉取
            Object obj = redisMQTemplate.opsForList().leftPop(key);
            while (!Objects.isNull(obj) && objects.size() < batchSize) {
                objects.add(obj);
                obj = redisMQTemplate.opsForList().leftPop(key);
            }
            if (!Objects.isNull(obj)){
                objects.add(obj);
            }
        }
        return objects;
    }

    @PreDestroy
    public void destory() {
        log.info("消费线程停止...");
        ThreadPoolExecutorFactory.shutDown();
    }
}

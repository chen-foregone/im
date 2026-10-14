package com.bx.imsimulator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bx.imsimulator.config.props.SimulatorProperties;
import com.bx.imsimulator.entity.User;
import com.bx.imsimulator.mapper.UserMapper;
import com.bx.imsimulator.service.SimulatorLoginService;
import com.bx.imsimulator.vo.BatchLoginResultVO;
import com.bx.imsimulator.ws.SimulatedSessionRegistry;
import com.bx.imsimulator.ws.SimulatedUserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class SimulatorLoginServiceImpl implements SimulatorLoginService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorLoginServiceImpl.class);
    private static final int SUCCESS_CODE = 200;

    private final SimulatorProperties properties;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SimulatedSessionRegistry sessionRegistry;

    public SimulatorLoginServiceImpl(SimulatorProperties properties, UserMapper userMapper, ObjectMapper objectMapper,
        @Qualifier("simulatorHttpClient") HttpClient httpClient, SimulatedSessionRegistry sessionRegistry) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void stopSessions() {
        sessionRegistry.closeAll();
    }

    @Override
    public BatchLoginResultVO batchLogin(Integer limit, Integer terminal) {
        int fetch = limit;
        LambdaQueryWrapper<User> qw =
            new LambdaQueryWrapper<User>().select(User::getId, User::getUserName).orderByAsc(User::getId)
                .last("LIMIT " + fetch);
        List<User> users = userMapper.selectList(qw);
        int count = users.size();
        if (count == 0) {
            return new BatchLoginResultVO(0, 0, 0, 0, 0, "no users in database", List.of());
        }

        sessionRegistry.closeAll();
        String base = properties.getApiUrl().replaceAll("/+$", "");
        URI loginUri = URI.create(base + "/login");
        int threads = Math.min(60, count);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.currentTimeMillis();
        List<Future<LineResult>> futures = new ArrayList<>(count);
        int term = terminal;
        for (User u : users) {
            futures.add(pool.submit(() -> simulateOne(loginUri, u, term)));
        }
        int httpOk = 0;
        int online = 0;
        List<Map<String, String>> samples = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String userName = users.get(i).getUserName();
            try {
                LineResult r = futures.get(i).get();
                if (r.httpSuccess()) {
                    httpOk++;
                }
                if (r.fullyOnline()) {
                    online++;
                }
                if (!r.fullyOnline() && r.failureReason().isPresent() && samples.size() < 10) {
                    samples.add(Map.of("userName", userName, "reason", r.failureReason().get()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                return new BatchLoginResultVO(count, httpOk, online, count - online, System.currentTimeMillis() - t0,
                    "interrupted", samples);
            } catch (ExecutionException e) {
                if (samples.size() < 10) {
                    samples.add(Map.of("userName", userName, "reason", String.valueOf(e.getCause())));
                }
                log.warn("simulate task failed user={}", userName, e.getCause());
            }
        }
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - t0;
        return new BatchLoginResultVO(count, httpOk, online, count - online, elapsed, "done", samples);
    }

    private LineResult simulateOne(URI loginUri, User u, int loginTerminal) {
        HttpLoginOutcome http = httpLogin(loginUri, u.getUserName(), loginTerminal);
        if (http.error().isPresent()) {
            return new LineResult(false, false, Optional.of("http: " + http.error().get()));
        }
        String devId = "sim-" + u.getId();
        SimulatedUserSession session =
            new SimulatedUserSession(properties, objectMapper, httpClient, u.getId(), u.getUserName(),
                http.accessToken(), devId);
        Optional<String> wsErr = session.connectBlocking();
        if (wsErr.isPresent()) {
            return new LineResult(true, false, Optional.of("ws: " + wsErr.get()));
        }
        sessionRegistry.register(u.getId(), session);
        return new LineResult(true, true, Optional.empty());
    }

    private HttpLoginOutcome httpLogin(URI loginUri, String userName, int loginTerminal) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("terminal", loginTerminal);
            body.put("userName", userName);
            body.put("password", properties.getLoginPassword());
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder().uri(loginUri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new HttpLoginOutcome(Optional.of("HTTP " + response.statusCode()), null);
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt();
            if (code != SUCCESS_CODE) {
                String msg = root.path("message").asText("");
                return new HttpLoginOutcome(Optional.of("code=" + code + (msg.isEmpty() ? "" : ", " + msg)), null);
            }
            String token = root.path("data").path("accessToken").asText("");
            if (token.isEmpty()) {
                return new HttpLoginOutcome(Optional.of("no accessToken in response"), null);
            }
            return new HttpLoginOutcome(Optional.empty(), token);
        } catch (Exception e) {
            log.debug("login http error user={}", userName, e);
            return new HttpLoginOutcome(Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage()), null);
        }
    }

    private record HttpLoginOutcome(Optional<String> error, String accessToken) {
    }

    private record LineResult(boolean httpSuccess, boolean fullyOnline, Optional<String> failureReason) {
    }

}

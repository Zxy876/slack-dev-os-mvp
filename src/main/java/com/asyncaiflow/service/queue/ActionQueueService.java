package com.asyncaiflow.service.queue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.asyncaiflow.domain.entity.ActionEntity;

@Service
public class ActionQueueService {

    private static final String ACTION_QUEUE_PREFIX = "action:queue:";
    private static final String ACTION_LOCK_PREFIX = "action:lock:";
    private static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    private static final String WORKSPACE_LOCK_PREFIX = "workspace:lock:";

    private final StringRedisTemplate redisTemplate;

    public ActionQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Value("${asyncaiflow.queue.lock-ttl-seconds:300}")
    private long lockTtlSeconds;

    @Value("${asyncaiflow.queue.heartbeat-ttl-seconds:60}")
    private long heartbeatTtlSeconds;

    public void enqueue(ActionEntity action) {
        enqueue(action, action.getType());
    }

    public void enqueue(ActionEntity action, String capability) {
        String queueCapability = (capability == null || capability.isBlank()) ? action.getType() : capability;
        redisTemplate.opsForList().leftPush(queueKey(queueCapability), action.getId().toString());
    }

    public Optional<Long> claimNextAction(List<String> capabilities, String workerId) {
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                continue;
            }
            while (true) {
                String rawActionId = redisTemplate.opsForList().rightPop(queueKey(capability));
                if (rawActionId == null) {
                    break;
                }
                Long actionId = Long.valueOf(rawActionId);
                Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                        lockKey(actionId),
                        workerId,
                        Duration.ofSeconds(lockTtlSeconds)
                );
                if (Boolean.TRUE.equals(locked)) {
                    return Optional.of(actionId);
                }
            }
        }
        return Optional.empty();
    }

    public void releaseLock(Long actionId) {
        redisTemplate.delete(lockKey(actionId));
    }

    public void refreshActionLock(Long actionId, String workerId, long ttlSeconds) {
        String currentOwner = redisTemplate.opsForValue().get(lockKey(actionId));
        if (workerId.equals(currentOwner)) {
            redisTemplate.expire(lockKey(actionId), Duration.ofSeconds(Math.max(1L, ttlSeconds)));
        }
    }

    public void refreshHeartbeat(String workerId) {
        redisTemplate.opsForValue().set(
                heartbeatKey(workerId),
                OffsetDateTime.now().toString(),
                Duration.ofSeconds(heartbeatTtlSeconds)
        );
    }

    /**
     * B-006 — Workspace single-writer mutex（SETNX）。
     *
     * 使用 Redis SET NX EX 原语对 workspaceKey 加锁，防止多 Worker 并发写入同一仓库/分支。
     *
     * @return true 表示加锁成功（当前调用者持有锁）；false 表示锁已被其他 Action 持有。
     */
    public boolean tryAcquireWorkspaceLock(String workspaceKey, String owner, long ttlSeconds) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                workspaceLockKey(workspaceKey),
                owner,
                Duration.ofSeconds(Math.max(1L, ttlSeconds))
        );
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * B-006 — 释放 workspace 互斥锁。
     *
     * 只有持有者（owner 匹配）才能释放，防止误删其他 Action 的锁。
     */
    public void releaseWorkspaceLock(String workspaceKey, String owner) {
        String currentOwner = redisTemplate.opsForValue().get(workspaceLockKey(workspaceKey));
        if (owner.equals(currentOwner)) {
            redisTemplate.delete(workspaceLockKey(workspaceKey));
        }
    }

    private String queueKey(String capability) {
        return ACTION_QUEUE_PREFIX + capability.trim();
    }

    private String lockKey(Long actionId) {
        return ACTION_LOCK_PREFIX + actionId;
    }

    private String heartbeatKey(String workerId) {
        return WORKER_HEARTBEAT_PREFIX + workerId;
    }

    private String workspaceLockKey(String workspaceKey) {
        return WORKSPACE_LOCK_PREFIX + workspaceKey;
    }
}
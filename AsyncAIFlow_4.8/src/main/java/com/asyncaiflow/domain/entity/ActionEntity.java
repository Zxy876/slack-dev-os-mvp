package com.asyncaiflow.domain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("action")
public class ActionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private String type;

    private String status;

    private String payload;

    private String workerId;

    private Integer retryCount;

    private Integer maxRetryCount;

    private Integer backoffSeconds;

    private Integer executionTimeoutSeconds;

    private LocalDateTime leaseExpireAt;

    private LocalDateTime nextRunAt;

    private LocalDateTime claimTime;

    private LocalDateTime firstRenewTime;

    private LocalDateTime lastRenewTime;

    private LocalDateTime submitTime;

    private LocalDateTime reclaimTime;

    private Integer leaseRenewSuccessCount;

    private Integer leaseRenewFailureCount;

    private LocalDateTime lastLeaseRenewAt;

    private LocalDateTime executionStartedAt;

    private Long lastExecutionDurationMs;

    private String lastReclaimReason;

    private String errorMessage;

    private String slackThreadId;

    private String notepadRef;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(Integer maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public Integer getBackoffSeconds() {
        return backoffSeconds;
    }

    public void setBackoffSeconds(Integer backoffSeconds) {
        this.backoffSeconds = backoffSeconds;
    }

    public Integer getExecutionTimeoutSeconds() {
        return executionTimeoutSeconds;
    }

    public void setExecutionTimeoutSeconds(Integer executionTimeoutSeconds) {
        this.executionTimeoutSeconds = executionTimeoutSeconds;
    }

    public LocalDateTime getLeaseExpireAt() {
        return leaseExpireAt;
    }

    public void setLeaseExpireAt(LocalDateTime leaseExpireAt) {
        this.leaseExpireAt = leaseExpireAt;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public LocalDateTime getClaimTime() {
        return claimTime;
    }

    public void setClaimTime(LocalDateTime claimTime) {
        this.claimTime = claimTime;
    }

    public LocalDateTime getFirstRenewTime() {
        return firstRenewTime;
    }

    public void setFirstRenewTime(LocalDateTime firstRenewTime) {
        this.firstRenewTime = firstRenewTime;
    }

    public LocalDateTime getLastRenewTime() {
        return lastRenewTime;
    }

    public void setLastRenewTime(LocalDateTime lastRenewTime) {
        this.lastRenewTime = lastRenewTime;
    }

    public LocalDateTime getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(LocalDateTime submitTime) {
        this.submitTime = submitTime;
    }

    public LocalDateTime getReclaimTime() {
        return reclaimTime;
    }

    public void setReclaimTime(LocalDateTime reclaimTime) {
        this.reclaimTime = reclaimTime;
    }

    public Integer getLeaseRenewSuccessCount() {
        return leaseRenewSuccessCount;
    }

    public void setLeaseRenewSuccessCount(Integer leaseRenewSuccessCount) {
        this.leaseRenewSuccessCount = leaseRenewSuccessCount;
    }

    public Integer getLeaseRenewFailureCount() {
        return leaseRenewFailureCount;
    }

    public void setLeaseRenewFailureCount(Integer leaseRenewFailureCount) {
        this.leaseRenewFailureCount = leaseRenewFailureCount;
    }

    public LocalDateTime getLastLeaseRenewAt() {
        return lastLeaseRenewAt;
    }

    public void setLastLeaseRenewAt(LocalDateTime lastLeaseRenewAt) {
        this.lastLeaseRenewAt = lastLeaseRenewAt;
    }

    public LocalDateTime getExecutionStartedAt() {
        return executionStartedAt;
    }

    public void setExecutionStartedAt(LocalDateTime executionStartedAt) {
        this.executionStartedAt = executionStartedAt;
    }

    public Long getLastExecutionDurationMs() {
        return lastExecutionDurationMs;
    }

    public void setLastExecutionDurationMs(Long lastExecutionDurationMs) {
        this.lastExecutionDurationMs = lastExecutionDurationMs;
    }

    public String getLastReclaimReason() {
        return lastReclaimReason;
    }

    public void setLastReclaimReason(String lastReclaimReason) {
        this.lastReclaimReason = lastReclaimReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSlackThreadId() {
        return slackThreadId;
    }

    public void setSlackThreadId(String slackThreadId) {
        this.slackThreadId = slackThreadId;
    }

    public String getNotepadRef() {
        return notepadRef;
    }

    public void setNotepadRef(String notepadRef) {
        this.notepadRef = notepadRef;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
package com.asyncaiflow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("action_dependency")
public class ActionDependencyEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private Long upstreamActionId;

    private Long downstreamActionId;

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

    public Long getUpstreamActionId() {
        return upstreamActionId;
    }

    public void setUpstreamActionId(Long upstreamActionId) {
        this.upstreamActionId = upstreamActionId;
    }

    public Long getDownstreamActionId() {
        return downstreamActionId;
    }

    public void setDownstreamActionId(Long downstreamActionId) {
        this.downstreamActionId = downstreamActionId;
    }
}
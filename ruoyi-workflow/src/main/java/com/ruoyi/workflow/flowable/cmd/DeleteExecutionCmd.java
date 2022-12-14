package com.ruoyi.workflow.flowable.cmd;

import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityManager;
import org.flowable.engine.impl.util.CommandContextUtil;

import java.io.Serializable;

/**
 * @description: 删除执行数据
 * @author: gssong
 * @date: 2022/4/15 20:30
 */
public class DeleteExecutionCmd implements Command<String>, Serializable {

    /**
     * 执行id
     */
    private final String executionId;

    public DeleteExecutionCmd(String executionId) {
        this.executionId = executionId;
    }

    @Override
    public String execute(CommandContext commandContext) {
        ExecutionEntityManager executionEntityManager = CommandContextUtil.getExecutionEntityManager();
        executionEntityManager.delete(executionId);
        return null;
    }
}

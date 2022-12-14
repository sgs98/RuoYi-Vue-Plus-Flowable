package com.ruoyi.workflow.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.redis.RedisUtils;
import com.ruoyi.workflow.common.constant.FlowConstant;
import com.ruoyi.workflow.domain.ActBusinessStatus;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.mapper.ActBusinessStatusMapper;
import com.ruoyi.workflow.service.IActBusinessStatusService;
import com.ruoyi.workflow.service.IActProcessNodeAssigneeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;


/**
 * 业务状态实体Service业务层处理
 *
 * @author gssong
 * @date 2021-10-10
 */
@Service
public class ActBusinessStatusServiceImpl extends ServiceImpl<ActBusinessStatusMapper, ActBusinessStatus> implements IActBusinessStatusService {

    @Autowired
    private IActProcessNodeAssigneeService iActProcessNodeAssigneeService;

    /**
     * @description: 修改业务状态
     * @param: businessKey 业务id
     * @param: statusEnum 业务状态
     * @param: processInstanceId 流程实例id
     * @param: tableName 表名
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/21
     */
    @Override
    public boolean updateState(String businessKey, BusinessStatusEnum statusEnum, String processInstanceId, String tableName) {
        try {
            // 1. 查询当前数据
            ActBusinessStatus bs = baseMapper.selectOne(new LambdaQueryWrapper<ActBusinessStatus>().eq(ActBusinessStatus::getBusinessKey, businessKey));
            // 2. 新增操作
            if (ObjectUtil.isNull(bs)) {
                ActBusinessStatus actBusinessStatus = new ActBusinessStatus();
                // 设置状态值
                actBusinessStatus.setStatus(statusEnum.getStatus());
                actBusinessStatus.setBusinessKey(businessKey);
                actBusinessStatus.setTableName(tableName.toLowerCase());
                // 只要判断不为null,就更新
                if (processInstanceId != null) {
                    actBusinessStatus.setProcessInstanceId(processInstanceId);
                }
                int insert = baseMapper.insert(actBusinessStatus);
                // 更新缓存
                if (insert == 1) {
                    RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + businessKey, actBusinessStatus, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
                    RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId, actBusinessStatus, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
                }
                return insert == 1;
            } else {
                // 设置状态值
                bs.setStatus(statusEnum.getStatus());
                bs.setBusinessKey(businessKey);
                // 只要判断不为null,就更新
                if (processInstanceId != null) {
                    bs.setProcessInstanceId(processInstanceId);
                }
                int update = baseMapper.updateById(bs);
                // 更新缓存
                if (update == 1) {
                    RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + businessKey, bs, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
                    RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId, bs, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
                }
                return update == 1;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException("更新失败:" + e.getMessage());
        }
    }

    @Override
    public boolean updateState(String businessKey, BusinessStatusEnum statusEnum, String procInstId) {
        return updateState(businessKey, statusEnum, procInstId, null);
    }

    @Override
    public ActBusinessStatus getInfoByBusinessKey(String businessKey) {
        ActBusinessStatus cacheBusinessStatus = RedisUtils.getCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + businessKey);
        if (cacheBusinessStatus != null) {
            return cacheBusinessStatus;
        }
        ActBusinessStatus actBusinessStatus = baseMapper.selectOne(new LambdaQueryWrapper<ActBusinessStatus>().eq(ActBusinessStatus::getBusinessKey, businessKey));
        RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + businessKey, actBusinessStatus, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
        return actBusinessStatus;
    }

    @Override
    public List<ActBusinessStatus> getListInfoByBusinessKey(List<String> businessKeys) {
        return baseMapper.selectList(new LambdaQueryWrapper<ActBusinessStatus>().in(ActBusinessStatus::getBusinessKey, businessKeys));
    }

    @Override
    @Transactional
    public boolean deleteStateByBusinessKey(String businessKey) {
        int delete = baseMapper.delete(new LambdaQueryWrapper<ActBusinessStatus>().eq(ActBusinessStatus::getBusinessKey, businessKey));
        ActBusinessStatus actBusinessStatus = getInfoByBusinessKey(businessKey);
        iActProcessNodeAssigneeService.deleteByProcessInstanceId(actBusinessStatus.getProcessInstanceId());
        if (actBusinessStatus != null) {
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + businessKey);
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + actBusinessStatus.getProcessInstanceId());
        }
        return delete == 1;
    }

    @Override
    @Transactional
    public boolean deleteStateByProcessInstId(String processInstanceId) {
        int delete = baseMapper.delete(new LambdaQueryWrapper<ActBusinessStatus>().eq(ActBusinessStatus::getProcessInstanceId, processInstanceId));
        ActBusinessStatus actBusinessStatus = getInfoByProcessInstId(processInstanceId);
        iActProcessNodeAssigneeService.deleteByProcessInstanceId(processInstanceId);
        if (actBusinessStatus != null) {
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId);
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + actBusinessStatus.getBusinessKey());
        }
        return delete == 1;
    }

    @Override
    public boolean deleteCache(String processInstanceId) {
        ActBusinessStatus actBusinessStatus = getInfoByProcessInstId(processInstanceId);
        if (actBusinessStatus != null) {
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId);
            RedisUtils.deleteObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + actBusinessStatus.getBusinessKey());
        }
        return true;
    }

    @Override
    public ActBusinessStatus getInfoByProcessInstId(String processInstanceId) {
        ActBusinessStatus cacheBusinessStatus = RedisUtils.getCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId);
        if (cacheBusinessStatus != null) {
            return cacheBusinessStatus;
        }
        ActBusinessStatus actBusinessStatus = baseMapper.selectOne(new LambdaQueryWrapper<ActBusinessStatus>().eq(ActBusinessStatus::getProcessInstanceId, processInstanceId));
        RedisUtils.setCacheObject(FlowConstant.CACHE_ACT_BUSINESS_STATUS_KEY + processInstanceId, actBusinessStatus, Duration.ofMinutes(FlowConstant.CACHE_EXPIRATION));
        return actBusinessStatus;
    }

    @Override
    public List<ActBusinessStatus> getInfoByProcessInstIds(List<String> processInstanceIds) {
        return baseMapper.selectList(new LambdaQueryWrapper<ActBusinessStatus>().in(ActBusinessStatus::getProcessInstanceId, processInstanceIds));
    }
}

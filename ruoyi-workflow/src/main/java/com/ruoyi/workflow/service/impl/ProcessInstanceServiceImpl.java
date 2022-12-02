package com.ruoyi.workflow.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.helper.LoginHelper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.workflow.domain.bo.ProcessInstBo;
import com.ruoyi.workflow.domain.bo.StartProcessBo;
import com.ruoyi.workflow.flowable.config.CustomDefaultProcessDiagramGenerator;
import com.ruoyi.workflow.common.constant.ActConstant;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.domain.ActBusinessStatus;
import com.ruoyi.workflow.domain.ActTaskNode;
import com.ruoyi.workflow.domain.bo.ProcessInstFinishBo;
import com.ruoyi.workflow.domain.bo.ProcessInstRunningBo;
import com.ruoyi.workflow.domain.vo.ActHistoryInfoVo;
import com.ruoyi.workflow.domain.vo.ProcessInstFinishVo;
import com.ruoyi.workflow.domain.vo.ProcessInstRunningVo;
import com.ruoyi.workflow.flowable.factory.WorkflowService;
import com.ruoyi.workflow.service.*;
import com.ruoyi.workflow.utils.WorkFlowUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Attachment;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @description: 流程实例业务层
 * @author: gssong
 * @date: 2021/10/10 18:38
 */
@Service
@RequiredArgsConstructor
public class ProcessInstanceServiceImpl extends WorkflowService implements IProcessInstanceService {

    private final IActBusinessStatusService iActBusinessStatusService;
    private final IUserService iUserService;
    private final IActTaskNodeService iActTaskNodeService;

    @Value("${flowable.activity-font-name}")
    private String activityFontName;

    @Value("${flowable.label-font-name}")
    private String labelFontName;

    @Value("${flowable.annotation-font-name}")
    private String annotationFontName;

    /**
     * @description: 提交申请，启动流程实例
     * @param: startProcessBo
     * @return: java.util.Map<java.lang.String, java.lang.Object>
     * @author: gssong
     * @date: 2021/10/10
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startWorkFlow(StartProcessBo startProcessBo) {
        Map<String, Object> map = new HashMap<>(16);
        if (StringUtils.isBlank(startProcessBo.getBusinessKey())) {
            throw new ServiceException("启动工作流时必须包含业务ID");
        }
        // 判断当前业务是否启动过流程
        List<HistoricProcessInstance> instanceList = historyService.createHistoricProcessInstanceQuery().processInstanceBusinessKey(startProcessBo.getBusinessKey()).list();
        TaskQuery taskQuery = taskService.createTaskQuery();
        List<Task> taskResult = taskQuery.processInstanceBusinessKey(startProcessBo.getBusinessKey()).list();
        if (CollUtil.isNotEmpty(instanceList)) {
            ActBusinessStatus info = iActBusinessStatusService.getInfoByBusinessKey(startProcessBo.getBusinessKey());
            if (ObjectUtil.isNotEmpty(info)) {
                BusinessStatusEnum.checkStatus(info.getStatus());
            }
            map.put("processInstanceId", taskResult.get(0).getProcessInstanceId());
            map.put("taskId", taskResult.get(0).getId());
            return map;
        }
        // 设置启动人
        Authentication.setAuthenticatedUserId(LoginHelper.getUserId().toString());
        // 启动流程实例（提交申请）
        Map<String, Object> variables = startProcessBo.getVariables();
        ProcessInstance pi;
        if (CollUtil.isNotEmpty(variables)) {
            pi = runtimeService.startProcessInstanceByKey(startProcessBo.getProcessKey(), startProcessBo.getBusinessKey(), variables);
        } else {
            pi = runtimeService.startProcessInstanceByKey(startProcessBo.getProcessKey(), startProcessBo.getBusinessKey());
        }
        // 将流程定义名称 作为 流程实例名称
        runtimeService.setProcessInstanceName(pi.getProcessInstanceId(), pi.getProcessDefinitionName());
        // 申请人执行流程
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        if (taskList.size() > 1) {
            throw new ServiceException("请检查流程第一个环节是否为申请人！");
        }
        taskService.setAssignee(taskList.get(0).getId(), LoginHelper.getUserId().toString());
        taskService.setVariable(taskList.get(0).getId(), "processInstanceId", pi.getProcessInstanceId());
        // 更新业务状态
        iActBusinessStatusService.updateState(startProcessBo.getBusinessKey(), BusinessStatusEnum.DRAFT, taskList.get(0).getProcessInstanceId(), startProcessBo.getTableName());

        map.put("processInstanceId", pi.getProcessInstanceId());
        map.put("taskId", taskList.get(0).getId());
        return map;
    }

    /**
     * @description: 通过流程实例id查询流程审批记录
     * @param: processInstanceId
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.ActHistoryInfoVo>
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    public List<ActHistoryInfoVo> getHistoryInfoList(String processInstanceId) {

        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        //查询任务办理记录
        List<HistoricTaskInstance> list = historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).orderByHistoricTaskInstanceEndTime().desc().list();
        list.stream().sorted(Comparator.comparing(HistoricTaskInstance::getEndTime, Comparator.nullsFirst(Date::compareTo))).collect(Collectors.toList());
        List<ActHistoryInfoVo> actHistoryInfoVoList = new ArrayList<>();
        for (HistoricTaskInstance historicTaskInstance : list) {
            ActHistoryInfoVo actHistoryInfoVo = new ActHistoryInfoVo();
            BeanUtils.copyProperties(historicTaskInstance, actHistoryInfoVo);
            actHistoryInfoVo.setStatus(actHistoryInfoVo.getEndTime() == null ? "待处理" : "已处理");
            List<Comment> taskComments = taskService.getTaskComments(historicTaskInstance.getId());
            if (CollUtil.isNotEmpty(taskComments)) {
                actHistoryInfoVo.setCommentId(taskComments.get(0).getId());
                String message = taskComments.stream().map(Comment::getFullMessage).collect(Collectors.joining("。"));
                if (StringUtils.isNotBlank(message)) {
                    actHistoryInfoVo.setComment(message);
                }
            }
            List<Attachment> taskAttachments = taskService.getTaskAttachments(historicTaskInstance.getId());
            actHistoryInfoVo.setFileList(taskAttachments);
            if (ObjectUtil.isNotEmpty(historicTaskInstance.getDurationInMillis())) {
                actHistoryInfoVo.setRunDuration(getDuration(historicTaskInstance.getDurationInMillis()));
            }
            actHistoryInfoVoList.add(actHistoryInfoVo);
        }
        //翻译人员名称
        if (CollUtil.isNotEmpty(actHistoryInfoVoList)) {
            List<Long> assigneeList = actHistoryInfoVoList.stream().map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(assigneeList)) {
                List<SysUser> sysUsers = iUserService.selectListUserByIds(assigneeList);
                actHistoryInfoVoList.forEach(e -> {
                    sysUsers.stream().filter(u -> u.getUserId().toString().equals(e.getAssignee())).findFirst().ifPresent(u -> {
                        e.setNickName(u.getNickName());
                    });
                });
            }
        }
        List<ActHistoryInfoVo> collect = new ArrayList<>();
        //待办理
        List<ActHistoryInfoVo> waitingTask = actHistoryInfoVoList.stream().filter(e -> e.getEndTime() == null).collect(Collectors.toList());
        //已办理
        List<ActHistoryInfoVo> finishTask = actHistoryInfoVoList.stream().filter(e -> e.getEndTime() != null).collect(Collectors.toList());
        collect.addAll(waitingTask);
        collect.addAll(finishTask);
        if (ObjectUtil.isNotEmpty(historicProcessInstance) && StringUtils.isNotBlank(historicProcessInstance.getDeleteReason())) {
            ActHistoryInfoVo actHistoryInfoVo = collect.get(0);
            actHistoryInfoVo.setHistoricProcessInstance(historicProcessInstance);
        }
        return collect;
    }

    /**
     * @description: 通过流程实例id获取历史流程图
     * @param: processInstId
     * @param: response
     * @return: void
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    public void getHistoryProcessImage(String processInstanceId, HttpServletResponse response) {
        // 设置页面不缓存
        response.setHeader("Pragma", "no-cache");
        response.addHeader("Cache-Control", "must-revalidate");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Cache-Control", "no-store");
        response.setDateHeader("Expires", 0);
        InputStream inputStream = null;
        try {
            String processDefinitionId;
            // 获取当前的流程实例
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            // 如果流程已经结束，则得到结束节点
            if (Objects.isNull(processInstance)) {
                HistoricProcessInstance pi = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();

                processDefinitionId = pi.getProcessDefinitionId();
            } else {// 如果流程没有结束，则取当前活动节点
                // 根据流程实例ID获得当前处于活动状态的ActivityId合集
                ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
                processDefinitionId = pi.getProcessDefinitionId();
            }

            // 获得活动的节点
            List<HistoricActivityInstance> highLightedFlowList = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list();

            List<String> highLightedFlows = new ArrayList<>();
            List<String> highLightedNodes = new ArrayList<>();
            //高亮
            for (HistoricActivityInstance tempActivity : highLightedFlowList) {
                if (ActConstant.SEQUENCE_FLOW.equals(tempActivity.getActivityType())) {
                    //高亮线
                    highLightedFlows.add(tempActivity.getActivityId());
                } else {
                    //高亮节点
                    if (tempActivity.getEndTime() == null) {
                        highLightedNodes.add(Color.RED.toString() + tempActivity.getActivityId());
                    } else {
                        highLightedNodes.add(tempActivity.getActivityId());
                    }
                }
            }
            List<String> highLightedNodeList = new ArrayList<>();
            //运行中的节点
            List<String> redNodeCollect = highLightedNodes.stream().filter(e -> e.contains(Color.RED.toString())).collect(Collectors.toList());
            //排除与运行中相同的节点
            for (String nodeId : highLightedNodes) {
                if (!nodeId.contains(Color.RED.toString()) && !redNodeCollect.contains(Color.RED + nodeId)) {
                    highLightedNodeList.add(nodeId);
                }
            }
            highLightedNodeList.addAll(redNodeCollect);
            BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
            CustomDefaultProcessDiagramGenerator diagramGenerator = new CustomDefaultProcessDiagramGenerator();
            inputStream = diagramGenerator.generateDiagram(bpmnModel, "png", highLightedNodeList, highLightedFlows, activityFontName, labelFontName, annotationFontName, null, 1.0, true);
            // 响应相关图片
            response.setContentType("image/png");

            byte[] bytes = IOUtils.toByteArray(inputStream);
            ServletOutputStream outputStream = response.getOutputStream();
            outputStream.write(bytes);
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * @description: 查询正在运行的流程实例
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.ProcessInstRunningVo>
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    public TableDataInfo<ProcessInstRunningVo> getProcessInstRunningByPage(ProcessInstRunningBo req) {
        List<ProcessInstRunningVo> list = null;
        ProcessInstanceQuery query = runtimeService.createProcessInstanceQuery();
        if (StringUtils.isNotBlank(req.getName())) {
            query.processInstanceNameLikeIgnoreCase(req.getName());
        }
        if (StringUtils.isNotBlank(req.getStartUserId())) {
            query.startedBy(req.getStartUserId());
        }
        List<ProcessInstance> processInstances = query.listPage(req.getPageNum(), req.getPageSize());
        List<ProcessInstRunningVo> processInstRunningVoList = new ArrayList<>();
        long total = query.count();
        //任务办理人
        List<SysUser> sysUserList = null;
        //流程实例id
        List<String> processInstanceIds = null;
        //任务集合
        List<Task> taskList = null;
        if (CollUtil.isNotEmpty(processInstances)) {
            processInstanceIds = processInstances.stream().map(ProcessInstance::getProcessInstanceId).collect(Collectors.toList());
            taskList = taskService.createTaskQuery().processInstanceIdIn(processInstanceIds).list().stream().filter(e -> StringUtils.isBlank(e.getParentTaskId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(taskList)) {
                List<Long> userIds = taskList.stream().filter(e -> StringUtils.isNotEmpty(e.getAssignee())).map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
                if (CollUtil.isNotEmpty(userIds)) {
                    sysUserList = iUserService.selectListUserByIds(userIds);
                }
            }
        }
        for (ProcessInstance pi : processInstances) {
            ProcessInstRunningVo processInstRunningVo = new ProcessInstRunningVo();
            BeanUtils.copyProperties(pi, processInstRunningVo);
            SysUser sysUser = iUserService.selectUserById(Long.valueOf(pi.getStartUserId()));
            if (ObjectUtil.isNotEmpty(sysUser)) {
                processInstRunningVo.setStartUserNickName(sysUser.getNickName());
            }
            processInstRunningVo.setIsSuspended(pi.isSuspended() ? "挂起" : "激活");
            //办理人
            StringBuilder currentNickName = new StringBuilder();
            //办理人id
            StringBuilder currentUserId = new StringBuilder();
            //办理人名称
            assert taskList != null;
            for (Task task : taskList) {
                String[] nickName = {null};
                if (StringUtils.isNotBlank(task.getAssignee()) && sysUserList != null) {
                    sysUserList.stream().filter(e -> e.getUserId().toString().equals(task.getAssignee())).findFirst().ifPresent(e -> nickName[0] = e.getNickName());
                }
                currentNickName.append("【任务名(").append(task.getName()).append(")->办理人(").append(nickName[0]).append(")】").append(",");
                currentUserId.append(task.getAssignee()).append(",");
            }
            if (StringUtils.isNotBlank(currentUserId)) {
                processInstRunningVo.setCurrentNickName(currentNickName.substring(0, currentNickName.toString().length() - 1));
                processInstRunningVo.setCurrentUserId(currentUserId.substring(0, currentUserId.toString().length() - 1));
            }
            processInstRunningVoList.add(processInstRunningVo);
        }
        if (CollUtil.isNotEmpty(processInstRunningVoList) && processInstanceIds != null) {
            //设置流程状态
            List<ActBusinessStatus> businessStatusList = iActBusinessStatusService.getInfoByProcessInstIds(new ArrayList<>(processInstanceIds));
            processInstRunningVoList.forEach(e -> businessStatusList.stream().filter(t -> t.getProcessInstanceId().equals(e.getProcessInstanceId())).findFirst().ifPresent(e::setActBusinessStatus));
            //设置流程发起人
            List<Long> userIds = processInstRunningVoList.stream().map(e -> Long.valueOf(e.getStartUserId())).collect(Collectors.toList());
            List<SysUser> sysUsers = iUserService.selectListUserByIds(userIds);
            if (CollUtil.isNotEmpty(sysUsers)) {
                processInstRunningVoList.forEach(e -> sysUsers.stream().filter(t -> t.getUserId().toString().equals(e.getStartUserId())).findFirst().ifPresent(t -> e.setStartUserNickName(t.getNickName())));
            }
            list = processInstRunningVoList.stream().sorted(Comparator.comparing(ProcessInstRunningVo::getStartTime).reversed()).collect(Collectors.toList());
        }
        return new TableDataInfo<>(list, total);
    }

    /**
     * @description: 挂起或激活流程实例
     * @param: data
     * @return: void
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateProcInstState(Map<String, Object> data) {
        try {
            String processInstId = data.get("processInstId").toString();
            String reason = data.get("reason").toString();
            // 1. 查询指定流程实例的数据
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstId).singleResult();
            // 2. 判断当前流程实例的状态
            if (processInstance.isSuspended()) {
                // 如果是已挂起，则更新为激活状态
                runtimeService.activateProcessInstanceById(processInstId);
            } else {
                // 如果是已激活，则更新为挂起状态
                runtimeService.suspendProcessInstanceById(processInstId);
            }
            ActBusinessStatus businessStatus = iActBusinessStatusService.getInfoByProcessInstId(processInstId);
            if (ObjectUtil.isEmpty(businessStatus)) {
                throw new ServiceException("当前流程异常，未生成act_business_status对象");
            }
            businessStatus.setSuspendedReason(reason);
            return iActBusinessStatusService.updateById(businessStatus);
        } catch (ServiceException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: 作废流程实例，不会删除历史记录
     * @param: processInstBo
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRuntimeProcessInst(ProcessInstBo processInstBo) {
        try {
            //1.删除流程实例
            if (StringUtils.isBlank(processInstBo.getProcessInstId())) {
                throw new ServiceException("流程实例id不能为空");
            }
            List<Task> list = taskService.createTaskQuery().processInstanceId(processInstBo.getProcessInstId()).list();
            List<Task> subTasks = list.stream().filter(e -> StringUtils.isNotBlank(e.getParentTaskId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(subTasks)) {
                subTasks.forEach(e -> taskService.deleteTask(e.getId()));
            }
            String deleteReason = LoginHelper.getUsername() + "作废了当前申请！";
            if (StringUtils.isNotBlank(processInstBo.getDeleteReason())) {
                deleteReason = LoginHelper.getUsername() + "作废理由:" + processInstBo.getDeleteReason();
            }
            runtimeService.deleteProcessInstance(processInstBo.getProcessInstId(), deleteReason);
            ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByProcessInstId(processInstBo.getProcessInstId());
            if (actBusinessStatus == null) {
                throw new ServiceException("当前流程异常，未生成act_business_status对象");
            }
            //2. 更新业务状态
            return iActBusinessStatusService.updateState(actBusinessStatus.getBusinessKey(), BusinessStatusEnum.INVALID,processInstBo.getProcessInstId());
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: 运行中的实例 删除程实例，删除历史记录，删除业务与流程关联信息
     * @param: processInstId
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRuntimeProcessAndHisInst(String processInstId) {
        try {
            //1.删除运行中流程实例
            List<Task> list = taskService.createTaskQuery().processInstanceId(processInstId).list();
            List<Task> subTasks = list.stream().filter(e -> StringUtils.isNotBlank(e.getParentTaskId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(subTasks)) {
                subTasks.forEach(e -> taskService.deleteTask(e.getId()));
            }
            runtimeService.deleteProcessInstance(processInstId, LoginHelper.getUserId() + "删除了当前流程申请");
            //2.删除历史记录
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstId).singleResult();
            if (ObjectUtil.isNotEmpty(historicProcessInstance)) {
                historyService.deleteHistoricProcessInstance(processInstId);
            }
            //3.删除业务状态
            iActBusinessStatusService.deleteStateByProcessInstId(processInstId);
            iActBusinessStatusService.deleteCache(processInstId);
            //4.删除保存的任务节点
            return iActTaskNodeService.deleteByInstanceId(processInstId);
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: 已完成的实例 删除程实例，删除历史记录，删除业务与流程关联信息
     * @param: processInstId
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFinishProcessAndHisInst(String processInstId) {
        try {
            //1.删除历史记录
            historyService.deleteHistoricProcessInstance(processInstId);
            //2.删除业务状态
            iActBusinessStatusService.deleteStateByProcessInstId(processInstId);
            //3.删除保存的任务节点
            return iActTaskNodeService.deleteByInstanceId(processInstId);
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: 查询已结束的流程实例
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.ProcessInstFinishVo>
     * @author: gssong
     * @date: 2021/10/23
     */
    @Override
    public TableDataInfo<ProcessInstFinishVo> getProcessInstFinishByPage(ProcessInstFinishBo req) {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery().finished() // 已结束的
            .orderByProcessInstanceEndTime().desc();
        if (StringUtils.isNotEmpty(req.getName())) {
            query.processInstanceNameLikeIgnoreCase(req.getName());
        }
        if (StringUtils.isNotEmpty(req.getStartUserId())) {
            query.startedBy(req.getStartUserId());
        }
        List<HistoricProcessInstance> list = query.listPage(req.getPageNum(), req.getPageSize());
        long total = query.count();
        List<ProcessInstFinishVo> processInstFinishVoList = new ArrayList<>();
        for (HistoricProcessInstance hpi : list) {
            ProcessInstFinishVo processInstFinishVo = new ProcessInstFinishVo();
            BeanUtils.copyProperties(hpi, processInstFinishVo);
            SysUser sysUser = iUserService.selectUserById(Long.valueOf(hpi.getStartUserId()));
            if (ObjectUtil.isNotEmpty(sysUser)) {
                processInstFinishVo.setStartUserNickName(sysUser.getNickName());
            }
            //业务状态
            ActBusinessStatus businessKey = iActBusinessStatusService.getInfoByBusinessKey(hpi.getBusinessKey());
            if (ObjectUtil.isNotNull(businessKey) && ObjectUtil.isNotEmpty(BusinessStatusEnum.getEumByStatus(businessKey.getStatus()))) {
                processInstFinishVo.setStatus(BusinessStatusEnum.getEumByStatus(businessKey.getStatus()).getDesc());
            }
            processInstFinishVoList.add(processInstFinishVo);
        }
        return new TableDataInfo<>(processInstFinishVoList, total);
    }

    @Override
    public String getProcessInstanceId(String businessKey) {
        String processInstanceId;
        ActBusinessStatus infoByBusinessKey = iActBusinessStatusService.getInfoByBusinessKey(businessKey);
        if (ObjectUtil.isNotEmpty(infoByBusinessKey) && (infoByBusinessKey.getStatus().equals(BusinessStatusEnum.FINISH.getStatus()) || infoByBusinessKey.getStatus().equals(BusinessStatusEnum.INVALID.getStatus()))) {
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceBusinessKey(businessKey).singleResult();
            processInstanceId = ObjectUtil.isNotEmpty(historicProcessInstance) ? historicProcessInstance.getId() : "";
        } else {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(businessKey).singleResult();
            processInstanceId = ObjectUtil.isNotEmpty(processInstance) ? processInstance.getProcessInstanceId() : "";
        }
        return processInstanceId;
    }

    /**
     * @description: 撤销申请
     * @param: processInstId
     * @return: boolean
     * @author: gssong
     * @date: 2022/1/21
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelProcessApply(String processInstId) {

        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstId).startedBy(LoginHelper.getUserId().toString()).singleResult();
        if (ObjectUtil.isNull(processInstance)) {
            throw new ServiceException("流程不是该审批人提交,撤销失败!");
        }
        //校验流程状态
        ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByBusinessKey(processInstance.getBusinessKey());
        if (ObjectUtil.isEmpty(actBusinessStatus)) {
            throw new ServiceException("流程异常");
        }
        BusinessStatusEnum.checkCancel(actBusinessStatus.getStatus());
        List<ActTaskNode> listActTaskNode = iActTaskNodeService.getListByInstanceId(processInstId);
        if (CollUtil.isEmpty(listActTaskNode)) {
            throw new ServiceException("未查询到撤回节点信息");
        }
        ActTaskNode actTaskNode = listActTaskNode.stream().filter(e -> e.getOrderNo() == 0).findFirst().orElse(null);
        if (ObjectUtil.isNull(actTaskNode)) {
            throw new ServiceException("未查询到撤回节点信息");
        }
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(processInstId).list();
        String processInstanceId = taskList.get(0).getProcessInstanceId();
        for (Task task : taskList) {
            if (task.isSuspended()) {
                throw new ServiceException("【" + task.getName() + "】任务已被挂起");
            }
            taskService.addComment(task.getId(), processInstanceId, "申请人撤销申请");
        }
        try {
            runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId).moveActivityIdsToSingleActivityId(taskList.stream().map(Task::getTaskDefinitionKey).collect(Collectors.toList()), actTaskNode.getNodeId()).changeState();
            List<Task> newTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            //处理并行会签环节重复节点
            if (CollUtil.isNotEmpty(newTaskList) && newTaskList.size() > 0) {
                List<Task> taskCollect = newTaskList.stream().filter(e -> e.getTaskDefinitionKey().equals(actTaskNode.getNodeId())).collect(Collectors.toList());
                if (taskCollect.size() > 1) {
                    taskCollect.remove(0);
                    taskCollect.forEach(WorkFlowUtils::deleteRuntimeTask);
                }
            }
            List<Task> cancelTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            if (CollUtil.isNotEmpty(cancelTaskList)) {
                for (Task task : cancelTaskList) {
                    taskService.setAssignee(task.getId(), LoginHelper.getUserId().toString());
                }
                iActTaskNodeService.deleteByInstanceId(processInstId);
            }
            return iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.CANCEL,processInstId);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException("撤销失败:" + e.getMessage());
        }
    }

    /**
     * @description: 获取xml
     * @param: processInstanceId
     * @return: java.util.Map<java.lang.String, java.lang.Object>
     * @author: gssong
     * @date: 2022/10/25 22:07
     */
    @Override
    public Map<String, Object> getXml(String processInstanceId) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> taskList = new ArrayList<>();
        HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        StringBuilder xml = new StringBuilder();
        ProcessDefinition processDefinition = repositoryService.getProcessDefinition(processInstance.getProcessDefinitionId());
        // 获得活动的节点
        List<HistoricActivityInstance> highLightedFlowList = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list();
        for (HistoricActivityInstance tempActivity : highLightedFlowList) {
            Map<String, Object> task = new HashMap<>();
            if (!ActConstant.SEQUENCE_FLOW.equals(tempActivity.getActivityType())) {
                if (tempActivity.getEndTime() == null) {
                    task.put("key", tempActivity.getActivityId());
                    task.put("completed", false);
                } else {
                    task.put("key", tempActivity.getActivityId());
                    task.put("completed", true);
                }
                taskList.add(task);
            }
        }
        //查询出运行中节点
        List<Map<String, Object>> runtimeNodeList = taskList.stream().filter(e -> !(Boolean) e.get("completed")).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(runtimeNodeList)) {
            Iterator<Map<String, Object>> iterator = taskList.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> next = iterator.next();
                runtimeNodeList.stream().filter(t -> t.get("key").equals(next.get("key")) && (Boolean) next.get("completed")).findFirst().ifPresent(t -> iterator.remove());
            }
        }
        map.put("taskList", taskList);
        InputStream inputStream;
        try {
            inputStream = repositoryService.getResourceAsStream(processDefinition.getDeploymentId(), processDefinition.getResourceName());
            xml.append(IOUtils.toString(inputStream, StandardCharsets.UTF_8));
            map.put("xml", xml.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }

    /**
     * 任务完成时间处理
     *
     * @param time
     * @return
     */
    private String getDuration(long time) {

        long day = time / (24 * 60 * 60 * 1000);
        long hour = (time / (60 * 60 * 1000) - day * 24);
        long minute = ((time / (60 * 1000)) - day * 24 * 60 - hour * 60);
        long second = (time / 1000 - day * 24 * 60 * 60 - hour * 60 * 60 - minute * 60);

        if (day > 0) {
            return day + "天" + hour + "小时" + minute + "分钟";
        }
        if (hour > 0) {
            return hour + "小时" + minute + "分钟";
        }
        if (minute > 0) {
            return minute + "分钟";
        }
        if (second > 0) {
            return second + "秒";
        } else {
            return 0 + "秒";
        }
    }
}

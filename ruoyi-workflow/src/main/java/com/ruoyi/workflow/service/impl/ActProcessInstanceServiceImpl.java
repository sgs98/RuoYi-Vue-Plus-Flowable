package com.ruoyi.workflow.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.helper.LoginHelper;
import com.ruoyi.common.utils.BeanCopyUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.workflow.domain.ActNodeAssignee;
import com.ruoyi.workflow.domain.bo.ProcessInstBo;
import com.ruoyi.workflow.domain.bo.StartProcessBo;
import com.ruoyi.workflow.domain.vo.*;
import com.ruoyi.workflow.flowable.config.CustomDefaultProcessDiagramGenerator;
import com.ruoyi.workflow.common.constant.FlowConstant;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.domain.ActBusinessStatus;
import com.ruoyi.workflow.domain.ActTaskNode;
import com.ruoyi.workflow.domain.bo.ProcessInstFinishBo;
import com.ruoyi.workflow.domain.bo.ProcessInstRunningBo;
import com.ruoyi.workflow.flowable.factory.WorkflowService;
import com.ruoyi.workflow.service.*;
import com.ruoyi.workflow.utils.ProcessRunningPathUtils;
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
 * @description: ?????????????????????
 * @author: gssong
 * @date: 2021/10/10 18:38
 */
@Service
@RequiredArgsConstructor
public class ActProcessInstanceServiceImpl extends WorkflowService implements IActProcessInstanceService {

    private final IActBusinessStatusService iActBusinessStatusService;
    private final IUserService iUserService;
    private final IActTaskNodeService iActTaskNodeService;
    private final IActNodeAssigneeService iActNodeAssigneeService;
    private final IActBusinessRuleService iActBusinessRuleService;

    @Value("${flowable.activity-font-name}")
    private String activityFontName;

    @Value("${flowable.label-font-name}")
    private String labelFontName;

    @Value("${flowable.annotation-font-name}")
    private String annotationFontName;

    /**
     * @description: ?????????????????????????????????
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
            throw new ServiceException("????????????????????????????????????ID");
        }
        // ???????????????????????????????????????
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
        // ???????????????
        Authentication.setAuthenticatedUserId(LoginHelper.getUserId().toString());
        // ????????????????????????????????????
        Map<String, Object> variables = startProcessBo.getVariables();
        ProcessInstance pi;
        if (CollUtil.isNotEmpty(variables)) {
            pi = runtimeService.startProcessInstanceByKey(startProcessBo.getProcessKey(), startProcessBo.getBusinessKey(), variables);
        } else {
            pi = runtimeService.startProcessInstanceByKey(startProcessBo.getProcessKey(), startProcessBo.getBusinessKey());
        }
        // ????????????????????? ?????? ??????????????????
        runtimeService.setProcessInstanceName(pi.getProcessInstanceId(), pi.getProcessDefinitionName());
        // ?????????????????????
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        if (taskList.size() > 1) {
            throw new ServiceException("???????????????????????????????????????????????????");
        }
        taskService.setAssignee(taskList.get(0).getId(), LoginHelper.getUserId().toString());
        taskService.setVariable(taskList.get(0).getId(), "processInstanceId", pi.getProcessInstanceId());
        // ??????????????????
        iActBusinessStatusService.updateState(startProcessBo.getBusinessKey(), BusinessStatusEnum.DRAFT, taskList.get(0).getProcessInstanceId(), startProcessBo.getTableName());

        map.put("processInstanceId", pi.getProcessInstanceId());
        map.put("taskId", taskList.get(0).getId());
        return map;
    }

    /**
     * @description: ??????????????????id????????????????????????
     * @param: processInstanceId
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.ActHistoryInfoVo>
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    public List<ActHistoryInfoVo> getHistoryInfoList(String processInstanceId) {

        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        //????????????????????????
        List<HistoricTaskInstance> list = historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).orderByHistoricTaskInstanceEndTime().desc().list();
        list.stream().sorted(Comparator.comparing(HistoricTaskInstance::getEndTime, Comparator.nullsFirst(Date::compareTo))).collect(Collectors.toList());
        List<ActHistoryInfoVo> actHistoryInfoVoList = new ArrayList<>();
        for (HistoricTaskInstance historicTaskInstance : list) {
            ActHistoryInfoVo actHistoryInfoVo = new ActHistoryInfoVo();
            BeanUtils.copyProperties(historicTaskInstance, actHistoryInfoVo);
            actHistoryInfoVo.setStatus(actHistoryInfoVo.getEndTime() == null ? "?????????" : "?????????");
            List<Comment> taskComments = taskService.getTaskComments(historicTaskInstance.getId());
            if (CollUtil.isNotEmpty(taskComments)) {
                actHistoryInfoVo.setCommentId(taskComments.get(0).getId());
                String message = taskComments.stream().map(Comment::getFullMessage).collect(Collectors.joining("???"));
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
        //??????????????????
        if (CollUtil.isNotEmpty(actHistoryInfoVoList)) {
            List<Long> assigneeList = actHistoryInfoVoList.stream().filter(e -> StringUtils.isNotBlank(e.getAssignee())).map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
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
        //?????????
        List<ActHistoryInfoVo> waitingTask = actHistoryInfoVoList.stream().filter(e -> e.getEndTime() == null).collect(Collectors.toList());
        //?????????
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
     * @description: ??????????????????id?????????????????????
     * @param: processInstId
     * @param: response
     * @return: void
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    public void getHistoryProcessImage(String processInstanceId, HttpServletResponse response) {
        // ?????????????????????
        response.setHeader("Pragma", "no-cache");
        response.addHeader("Cache-Control", "must-revalidate");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Cache-Control", "no-store");
        response.setDateHeader("Expires", 0);
        InputStream inputStream = null;
        try {
            String processDefinitionId;
            // ???????????????????????????
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            // ????????????????????????????????????????????????
            if (Objects.isNull(processInstance)) {
                HistoricProcessInstance pi = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();

                processDefinitionId = pi.getProcessDefinitionId();
            } else {// ???????????????????????????????????????????????????
                // ??????????????????ID?????????????????????????????????ActivityId??????
                ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
                processDefinitionId = pi.getProcessDefinitionId();
            }

            // ?????????????????????
            List<HistoricActivityInstance> highLightedFlowList = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list();

            List<String> highLightedFlows = new ArrayList<>();
            List<String> highLightedNodes = new ArrayList<>();
            //??????
            for (HistoricActivityInstance tempActivity : highLightedFlowList) {
                if (FlowConstant.SEQUENCE_FLOW.equals(tempActivity.getActivityType())) {
                    //?????????
                    highLightedFlows.add(tempActivity.getActivityId());
                } else {
                    //????????????
                    if (tempActivity.getEndTime() == null) {
                        highLightedNodes.add(Color.RED.toString() + tempActivity.getActivityId());
                    } else {
                        highLightedNodes.add(tempActivity.getActivityId());
                    }
                }
            }
            List<String> highLightedNodeList = new ArrayList<>();
            //??????????????????
            List<String> redNodeCollect = highLightedNodes.stream().filter(e -> e.contains(Color.RED.toString())).collect(Collectors.toList());
            //?????????????????????????????????
            for (String nodeId : highLightedNodes) {
                if (!nodeId.contains(Color.RED.toString()) && !redNodeCollect.contains(Color.RED + nodeId)) {
                    highLightedNodeList.add(nodeId);
                }
            }
            highLightedNodeList.addAll(redNodeCollect);
            BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
            CustomDefaultProcessDiagramGenerator diagramGenerator = new CustomDefaultProcessDiagramGenerator();
            inputStream = diagramGenerator.generateDiagram(bpmnModel, "png", highLightedNodeList, highLightedFlows, activityFontName, labelFontName, annotationFontName, null, 1.0, true);
            // ??????????????????
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
     * @description: ?????????????????????????????????
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
        //???????????????
        List<SysUser> sysUserList = null;
        //????????????id
        List<String> processInstanceIds = null;
        //????????????
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
            processInstRunningVo.setIsSuspended(pi.isSuspended() ? "??????" : "??????");
            //?????????
            StringBuilder currentNickName = new StringBuilder();
            //?????????id
            StringBuilder currentUserId = new StringBuilder();
            //???????????????
            assert taskList != null;
            for (Task task : taskList) {
                String[] nickName = {null};
                if (StringUtils.isNotBlank(task.getAssignee()) && sysUserList != null) {
                    sysUserList.stream().filter(e -> e.getUserId().toString().equals(task.getAssignee())).findFirst().ifPresent(e -> nickName[0] = e.getNickName());
                }
                currentNickName.append("????????????(").append(task.getName()).append(")->?????????(").append(nickName[0]).append(")???").append(",");
                currentUserId.append(task.getAssignee()).append(",");
            }
            if (StringUtils.isNotBlank(currentUserId)) {
                processInstRunningVo.setCurrentNickName(currentNickName.substring(0, currentNickName.toString().length() - 1));
                processInstRunningVo.setCurrentUserId(currentUserId.substring(0, currentUserId.toString().length() - 1));
            }
            processInstRunningVoList.add(processInstRunningVo);
        }
        if (CollUtil.isNotEmpty(processInstRunningVoList) && processInstanceIds != null) {
            //??????????????????
            List<ActBusinessStatus> businessStatusList = iActBusinessStatusService.getInfoByProcessInstIds(new ArrayList<>(processInstanceIds));
            processInstRunningVoList.forEach(e -> businessStatusList.stream().filter(t -> t.getProcessInstanceId().equals(e.getProcessInstanceId())).findFirst().ifPresent(e::setActBusinessStatus));
            //?????????????????????
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
     * @description: ???????????????????????????
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
            // 1. ?????????????????????????????????
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstId).singleResult();
            // 2. ?????????????????????????????????
            if (processInstance.isSuspended()) {
                // ?????????????????????????????????????????????
                runtimeService.activateProcessInstanceById(processInstId);
            } else {
                // ?????????????????????????????????????????????
                runtimeService.suspendProcessInstanceById(processInstId);
            }
            ActBusinessStatus businessStatus = iActBusinessStatusService.getInfoByProcessInstId(processInstId);
            if (ObjectUtil.isEmpty(businessStatus)) {
                throw new ServiceException("??????????????????????????????act_business_status??????");
            }
            businessStatus.setSuspendedReason(reason);
            return iActBusinessStatusService.updateById(businessStatus);
        } catch (ServiceException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ?????????????????????????????????????????????
     * @param: processInstBo
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRuntimeProcessInst(ProcessInstBo processInstBo) {
        try {
            //1.??????????????????
            if (StringUtils.isBlank(processInstBo.getProcessInstId())) {
                throw new ServiceException("????????????id????????????");
            }
            List<Task> list = taskService.createTaskQuery().processInstanceId(processInstBo.getProcessInstId()).list();
            List<Task> subTasks = list.stream().filter(e -> StringUtils.isNotBlank(e.getParentTaskId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(subTasks)) {
                subTasks.forEach(e -> taskService.deleteTask(e.getId()));
            }
            String deleteReason = LoginHelper.getUsername() + "????????????????????????";
            if (StringUtils.isNotBlank(processInstBo.getDeleteReason())) {
                deleteReason = LoginHelper.getUsername() + "????????????:" + processInstBo.getDeleteReason();
            }
            runtimeService.deleteProcessInstance(processInstBo.getProcessInstId(), deleteReason);
            ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByProcessInstId(processInstBo.getProcessInstId());
            if (actBusinessStatus == null) {
                throw new ServiceException("??????????????????????????????act_business_status??????");
            }
            //2. ??????????????????
            return iActBusinessStatusService.updateState(actBusinessStatus.getBusinessKey(), BusinessStatusEnum.INVALID, processInstBo.getProcessInstId());
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ?????????????????? ????????????????????????????????????????????????????????????????????????
     * @param: processInstId
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRuntimeProcessAndHisInst(String processInstId) {
        try {
            //1.???????????????????????????
            List<Task> list = taskService.createTaskQuery().processInstanceId(processInstId).list();
            List<Task> subTasks = list.stream().filter(e -> StringUtils.isNotBlank(e.getParentTaskId())).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(subTasks)) {
                subTasks.forEach(e -> taskService.deleteTask(e.getId()));
            }
            runtimeService.deleteProcessInstance(processInstId, LoginHelper.getUserId() + "???????????????????????????");
            //2.??????????????????
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstId).singleResult();
            if (ObjectUtil.isNotEmpty(historicProcessInstance)) {
                historyService.deleteHistoricProcessInstance(processInstId);
            }
            //3.??????????????????
            iActBusinessStatusService.deleteStateByProcessInstId(processInstId);
            iActBusinessStatusService.deleteCache(processInstId);
            //4.???????????????????????????
            return iActTaskNodeService.deleteByInstanceId(processInstId);
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ?????????????????? ????????????????????????????????????????????????????????????????????????
     * @param: processInstId
     * @return: boolean
     * @author: gssong
     * @date: 2021/10/16
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFinishProcessAndHisInst(String processInstId) {
        try {
            //1.??????????????????
            historyService.deleteHistoricProcessInstance(processInstId);
            //2.??????????????????
            iActBusinessStatusService.deleteStateByProcessInstId(processInstId);
            //3.???????????????????????????
            return iActTaskNodeService.deleteByInstanceId(processInstId);
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ??????????????????????????????
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.ProcessInstFinishVo>
     * @author: gssong
     * @date: 2021/10/23
     */
    @Override
    public TableDataInfo<ProcessInstFinishVo> getProcessInstFinishByPage(ProcessInstFinishBo req) {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery().finished() // ????????????
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
            //????????????
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
            processInstanceId = ObjectUtil.isNotEmpty(historicProcessInstance) ? historicProcessInstance.getId() : StrUtil.EMPTY;
        } else {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(businessKey).singleResult();
            processInstanceId = ObjectUtil.isNotEmpty(processInstance) ? processInstance.getProcessInstanceId() : StrUtil.EMPTY;
        }
        return processInstanceId;
    }

    /**
     * @description: ????????????
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
            throw new ServiceException("??????????????????????????????,????????????!");
        }
        //??????????????????
        ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByBusinessKey(processInstance.getBusinessKey());
        if (ObjectUtil.isEmpty(actBusinessStatus)) {
            throw new ServiceException("????????????");
        }
        BusinessStatusEnum.checkCancel(actBusinessStatus.getStatus());
        List<ActTaskNode> listActTaskNode = iActTaskNodeService.getListByInstanceId(processInstId);
        if (CollUtil.isEmpty(listActTaskNode)) {
            throw new ServiceException("??????????????????????????????");
        }
        ActTaskNode actTaskNode = listActTaskNode.stream().filter(e -> e.getOrderNo() == 0).findFirst().orElse(null);
        if (ObjectUtil.isNull(actTaskNode)) {
            throw new ServiceException("??????????????????????????????");
        }
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(processInstId).list();
        String processInstanceId = taskList.get(0).getProcessInstanceId();
        for (Task task : taskList) {
            if (task.isSuspended()) {
                throw new ServiceException("???" + task.getName() + "?????????????????????");
            }
            taskService.addComment(task.getId(), processInstanceId, "?????????????????????");
        }
        try {
            runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId).moveActivityIdsToSingleActivityId(taskList.stream().map(Task::getTaskDefinitionKey).collect(Collectors.toList()), actTaskNode.getNodeId()).changeState();
            List<Task> newTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            //????????????????????????????????????
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
            return iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.CANCEL, processInstId);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException("????????????:" + e.getMessage());
        }
    }

    /**
     * @description: ??????xml
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
        // ?????????????????????
        List<HistoricActivityInstance> highLightedFlowList = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).orderByHistoricActivityInstanceStartTime().asc().list();
        for (HistoricActivityInstance tempActivity : highLightedFlowList) {
            Map<String, Object> task = new HashMap<>();
            if (!FlowConstant.SEQUENCE_FLOW.equals(tempActivity.getActivityType()) &&
                !FlowConstant.PARALLEL_GATEWAY.equals(tempActivity.getActivityType()) &&
                !FlowConstant.EXCLUSIVE_GATEWAY.equals(tempActivity.getActivityType()) &&
                !FlowConstant.INCLUSIVE_GATEWAY.equals(tempActivity.getActivityType())
            ) {
                task.put("key", tempActivity.getActivityId());
                task.put("completed", tempActivity.getEndTime() != null);
                taskList.add(task);
            }
        }
        //????????????????????????
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
     * @description: ???????????????????????????
     * @param: processInstanceId
     * @return: java.util.Map<java.lang.String, java.lang.Object>
     * @author: gssong
     * @date: 2022/12/4 18:02
     */
    @Override
    public Map<String, Object> getExecutableNode(String processInstanceId) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> taskList = new ArrayList<>();
        StringBuilder xml = new StringBuilder();
        HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        ProcessDefinition processDefinition = repositoryService.getProcessDefinition(processInstance.getProcessDefinitionId());
        List<ProcessNodePath> processNodeList = ProcessRunningPathUtils.getProcessNodeList(processInstanceId);

        List<ActNodeAssignee> actNodeAssignees = iActNodeAssigneeService.getInfoByProcessDefinitionId(processDefinition.getId());

        for (ProcessNodePath processNodePath : processNodeList) {
            Map<String, Object> task = new HashMap<>();
            task.put("key", processNodePath.getNodeId());
            task.put("completed", true);
            taskList.add(task);
        }
        List<Task> list = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        Map<String, Object> variables = runtimeService.getVariables(list.get(0).getExecutionId());
        List<ProcessNodePath> processNodePaths = new ArrayList<>();
        if (CollUtil.isNotEmpty(actNodeAssignees)) {
            for (ProcessNodePath processNodePath : processNodeList) {
                actNodeAssignees.stream().filter(e -> e.getNodeId().equals(processNodePath.getNodeId())).findFirst()
                    .ifPresent(e -> {
                        ProcessNodePath node = new ProcessNodePath();
                        BeanCopyUtils.copy(processNodePath, node);
                        node.setAssignee(e.getAssignee());
                        node.setAssigneeId(e.getAssigneeId());
                        node.setTransactor(e.getAssignee());
                        node.setTransactorId(e.getAssigneeId());
                        node.setChooseWay(e.getChooseWay());
                        if (FlowConstant.WORKFLOW_RULE.equals(e.getChooseWay())) {
                            ActBusinessRuleVo actBusinessRuleVo = iActBusinessRuleService.queryById(e.getBusinessRuleId());
                            if (actBusinessRuleVo == null) {
                                throw new ServiceException("???????????????");
                            }
                            List<String> assignList = WorkFlowUtils.ruleAssignList(actBusinessRuleVo, processNodePath.getNodeName(), variables);
                            node.setChooseWay(FlowConstant.WORKFLOW_PERSON);
                            node.setAssigneeId(String.join(",", assignList));
                            node.setAssignee(String.join(",", assignList));
                        }
                        if (processNodePath.getFirst()) {
                            SysUser sysUser = iUserService.selectUserById(Long.valueOf(processInstance.getStartUserId()));
                            node.setAssignee(sysUser.getNickName());
                            node.setAssigneeId(sysUser.getUserId().toString());
                            node.setChooseWay(FlowConstant.WORKFLOW_PERSON);
                            node.setTransactor(sysUser.getNickName());
                            node.setTransactorId(sysUser.getUserId().toString());
                        }
                        if (e.getIsShow()) {
                            node.setTransactor(StrUtil.EMPTY);
                            node.setTransactorId(StrUtil.EMPTY);
                        }
                        if ((FlowConstant.WORKFLOW_ROLE.equals(e.getChooseWay()) || FlowConstant.WORKFLOW_DEPT.equals(e.getChooseWay())) && !e.getIsShow()) {
                            if (FlowConstant.WORKFLOW_ROLE.equals(e.getChooseWay())) {
                                List<Long> roleIds = Arrays.stream(node.getAssigneeId().split(",")).map(Long::valueOf).collect(Collectors.toList());
                                List<SysUser> userList = iUserService.getUserListByRoleIds(roleIds);
                                if (CollUtil.isEmpty(userList)) {
                                    throw new ServiceException("???" + node.getNodeName() + "??????????????????????????????");
                                }
                                String userIds = userList.stream().map(u -> u.getUserId().toString()).collect(Collectors.joining(","));
                                String nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.joining(","));
                                node.setAssigneeId(userIds);
                                node.setAssignee(nickNames);
                            }
                            if (FlowConstant.WORKFLOW_DEPT.equals(e.getChooseWay())) {
                                List<Long> deptIds = Arrays.stream(node.getAssigneeId().split(",")).map(Long::valueOf).collect(Collectors.toList());
                                List<SysUser> userList = iUserService.getUserListByDeptIds(deptIds);
                                if (CollUtil.isEmpty(userList)) {
                                    throw new ServiceException("???" + node.getNodeName() + "??????????????????????????????");
                                }
                                String userIds = userList.stream().map(u -> u.getUserId().toString()).collect(Collectors.joining(","));
                                String nickNames = userList.stream().map(SysUser::getNickName).collect(Collectors.joining(","));
                                node.setAssigneeId(userIds);
                                node.setAssignee(nickNames);
                            }
                        }
                        node.setDisabled(e.getIsShow());
                        processNodePaths.add(node);
                    });
            }
        }
        map.put("processNodeList", processNodePaths);
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
     * ????????????????????????
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
            return day + "???" + hour + "??????" + minute + "??????";
        }
        if (hour > 0) {
            return hour + "??????" + minute + "??????";
        }
        if (minute > 0) {
            return minute + "??????";
        }
        if (second > 0) {
            return second + "???";
        } else {
            return 0 + "???";
        }
    }
}

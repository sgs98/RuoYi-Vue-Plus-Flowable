package com.ruoyi.workflow.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageQuery;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.helper.LoginHelper;
import com.ruoyi.common.utils.JsonUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.workflow.common.constant.FlowConstant;
import com.ruoyi.workflow.common.enums.BusinessStatusEnum;
import com.ruoyi.workflow.domain.*;
import com.ruoyi.workflow.domain.bo.*;
import com.ruoyi.workflow.domain.vo.*;
import com.ruoyi.workflow.flowable.cmd.AddSequenceMultiInstanceCmd;
import com.ruoyi.workflow.flowable.cmd.AttachmentCmd;
import com.ruoyi.workflow.utils.CompleteTaskUtils;
import com.ruoyi.workflow.flowable.cmd.DeleteSequenceMultiInstanceCmd;
import com.ruoyi.workflow.flowable.factory.WorkflowService;
import com.ruoyi.workflow.mapper.ActTaskMapper;
import com.ruoyi.workflow.service.*;
import com.ruoyi.workflow.utils.WorkFlowUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.IOUtils;
import org.flowable.bpmn.model.*;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.bpmn.behavior.ParallelMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.behavior.SequentialMultiInstanceBehavior;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Attachment;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static com.ruoyi.common.helper.LoginHelper.getUserId;

/**
 * @description: ???????????????
 * @author: gssong
 * @date: 2021/10/17 14:57
 */
@Service
@RequiredArgsConstructor
public class ActTaskServiceImpl extends WorkflowService implements IActTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ActTaskServiceImpl.class);

    private final IUserService iUserService;

    private final IActBusinessStatusService iActBusinessStatusService;

    private final IActTaskNodeService iActTaskNodeService;

    private final IActNodeAssigneeService iActNodeAssigneeService;

    private final IActBusinessRuleService iActBusinessRuleService;

    private final IActHiTaskInstService iActHiTaskInstService;

    private final ManagementService managementService;

    private final ActTaskMapper actTaskMapper;

    private final IActProcessDefSetting iActProcessDefSetting;

    private final IActProcessInstanceService iActProcessInstanceService;


    /**
     * @description: ?????????????????????????????????
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.TaskWaitingVo>
     * @author: gssong
     * @date: 2021/10/17
     */
    @Override
    public TableDataInfo<TaskWaitingVo> getTaskWaitByPage(TaskBo req) {
        //???????????????
        String currentUserId = LoginHelper.getLoginUser().getUserId().toString();
        TaskQuery query = taskService.createTaskQuery()
            //????????????????????????
            .taskCandidateOrAssigned(currentUserId)
            .orderByTaskCreateTime().asc();
        if (StringUtils.isNotEmpty(req.getTaskName())) {
            query.taskNameLikeIgnoreCase("%" + req.getTaskName() + "%");
        }
        if (StringUtils.isNotEmpty(req.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + req.getProcessDefinitionName() + "%");
        }
        List<Task> taskList = query.listPage(req.getPageNum(), req.getPageSize());
        if (CollectionUtil.isEmpty(taskList)) {
            return new TableDataInfo<>();
        }
        long total = query.count();
        List<TaskWaitingVo> list = new ArrayList<>();
        //????????????id
        Set<String> processInstanceIds = taskList.stream().map(Task::getProcessInstanceId).collect(Collectors.toSet());
        //????????????id
        List<String> processDefinitionIds = taskList.stream().map(Task::getProcessDefinitionId).collect(Collectors.toList());
        //??????????????????
        List<ProcessInstance> processInstanceList = runtimeService.createProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        //????????????????????????
        List<ActProcessDefSettingVo> processDefSettingLists = iActProcessDefSetting.getProcessDefSettingByDefIds(processDefinitionIds);
        //?????????
        List<Long> assignees = taskList.stream().filter(e -> StringUtils.isNotBlank(e.getAssignee())).map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
        //???????????????
        List<Long> userIds = processInstanceList.stream().map(e -> Long.valueOf(e.getStartUserId())).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(assignees)) {
            userIds.addAll(assignees);
        }
        List<SysUser> userList = iUserService.selectListUserByIds(userIds);

        for (Task task : taskList) {
            TaskWaitingVo taskWaitingVo = new TaskWaitingVo();
            BeanUtils.copyProperties(task, taskWaitingVo);
            taskWaitingVo.setAssigneeId(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            taskWaitingVo.setSuspensionState(task.isSuspended());
            taskWaitingVo.setProcessStatus(!task.isSuspended() ? "??????" : "??????");
            processInstanceList.stream().filter(e -> e.getProcessInstanceId().equals(task.getProcessInstanceId())).findFirst()
                .ifPresent(e -> {
                    //???????????????
                    String startUserId = e.getStartUserId();
                    taskWaitingVo.setStartUserId(startUserId);
                    if (StringUtils.isNotBlank(startUserId)) {
                        userList.stream().filter(u -> u.getUserId().toString().equals(startUserId)).findFirst().ifPresent(u -> {
                            taskWaitingVo.setStartUserNickName(u.getNickName());
                        });
                    }
                    taskWaitingVo.setProcessDefinitionVersion(e.getProcessDefinitionVersion());
                    taskWaitingVo.setProcessDefinitionName(e.getProcessDefinitionName());
                    taskWaitingVo.setBusinessKey(e.getBusinessKey());
                });
            // ????????????????????????
            processDefSettingLists.stream().filter(e -> e.getProcessDefinitionId().equals(task.getProcessDefinitionId())).findFirst()
                .ifPresent(taskWaitingVo::setActProcessDefSetting);
            list.add(taskWaitingVo);
        }
        if (CollectionUtil.isNotEmpty(list)) {
            //?????????????????????
            list.forEach(e -> {
                List<IdentityLink> identityLinkList = WorkFlowUtils.getCandidateUser(e.getId());
                if (CollectionUtil.isNotEmpty(identityLinkList)) {
                    List<String> collectType = identityLinkList.stream().map(IdentityLink::getType).collect(Collectors.toList());
                    if (StringUtils.isBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                        e.setIsClaim(false);
                    } else if (StringUtils.isNotBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                        e.setIsClaim(true);
                    }
                }
            });
            //???????????????
            if (CollectionUtil.isNotEmpty(userList)) {
                list.forEach(e -> userList.stream().filter(t -> StringUtils.isNotBlank(e.getAssignee()) && t.getUserId().toString().equals(e.getAssigneeId().toString()))
                    .findFirst().ifPresent(t -> {
                        e.setAssignee(t.getNickName());
                        e.setAssigneeId(t.getUserId());
                    }));
            }
            //??????id??????
            List<String> businessKeyList = list.stream().map(TaskWaitingVo::getBusinessKey).collect(Collectors.toList());
            List<ActBusinessStatus> infoList = iActBusinessStatusService.getListInfoByBusinessKey(businessKeyList);
            if (CollectionUtil.isNotEmpty(infoList)) {
                list.forEach(e -> infoList.stream().filter(t -> t.getBusinessKey().equals(e.getBusinessKey()))
                    .findFirst().ifPresent(e::setActBusinessStatus));
            }
        }
        return new TableDataInfo<>(list, total);
    }

    /**
     * @description: ?????????sql?????????????????????????????????
     * @param: req
     * @param: pageQuery
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.TaskWaitingVo>
     * @author: gssong
     * @date: 2022/11/7
     */
    @Override
    public TableDataInfo<TaskWaitingVo> getCustomTaskWaitByPage(TaskBo req, PageQuery pageQuery) {
        String assignee = LoginHelper.getUserId().toString();
        if (StringUtils.isBlank(assignee)) {
            throw new ServiceException("???????????????id??????");
        }
        QueryWrapper<TaskWaitingVo> wrapper = Wrappers.query();
        Page<TaskWaitingVo> page = actTaskMapper.getCustomTaskWaitByPage(pageQuery.build(), wrapper, assignee);
        if (CollectionUtil.isEmpty(page.getRecords())) {
            return new TableDataInfo<>();
        }
        List<TaskWaitingVo> taskList = page.getRecords();
        //????????????id
        Set<String> processInstanceIds = taskList.stream().map(TaskWaitingVo::getProcessInstanceId).collect(Collectors.toSet());
        //????????????id
        List<String> processDefinitionIds = taskList.stream().map(TaskWaitingVo::getProcessDefinitionId).collect(Collectors.toList());
        //??????????????????
        List<ProcessInstance> processInstanceList = runtimeService.createProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        //????????????????????????
        List<ActProcessDefSettingVo> processDefSettingLists = iActProcessDefSetting.getProcessDefSettingByDefIds(processDefinitionIds);
        //?????????
        List<Long> assignees = taskList.stream().filter(e -> StringUtils.isNotBlank(e.getAssignee())).map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
        //???????????????
        List<Long> userIds = processInstanceList.stream().map(e -> Long.valueOf(e.getStartUserId())).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(assignees)) {
            userIds.addAll(assignees);
        }
        List<SysUser> userList = iUserService.selectListUserByIds(userIds);
        for (TaskWaitingVo task : taskList) {
            task.setAssigneeId(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            task.setProcessStatus(task.getSuspensionState() ? "??????" : "??????");
            // ??????????????????
            processInstanceList.stream().filter(e -> e.getProcessInstanceId().equals(task.getProcessInstanceId())).findFirst()
                .ifPresent(e -> {
                    //???????????????
                    String startUserId = e.getStartUserId();
                    task.setStartUserId(startUserId);
                    if (StringUtils.isNotBlank(startUserId)) {
                        userList.stream().filter(u -> u.getUserId().toString().equals(startUserId)).findFirst().ifPresent(u -> {
                            task.setStartUserNickName(u.getNickName());
                        });
                    }
                    task.setProcessDefinitionVersion(e.getProcessDefinitionVersion());
                    task.setProcessDefinitionName(e.getProcessDefinitionName());
                    task.setBusinessKey(e.getBusinessKey());
                });
            // ????????????????????????
            processDefSettingLists.stream().filter(e -> e.getProcessDefinitionId().equals(task.getProcessDefinitionId())).findFirst()
                .ifPresent(task::setActProcessDefSetting);
        }
        //?????????????????????
        taskList.forEach(e -> {
            List<IdentityLink> identityLinkList = WorkFlowUtils.getCandidateUser(e.getId());
            if (CollectionUtil.isNotEmpty(identityLinkList)) {
                List<String> collectType = identityLinkList.stream().map(IdentityLink::getType).collect(Collectors.toList());
                if (StringUtils.isBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                    e.setIsClaim(false);
                } else if (StringUtils.isNotBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                    e.setIsClaim(true);
                }
            }
        });
        //???????????????
        if (CollectionUtil.isNotEmpty(userList)) {
            taskList.forEach(e -> userList.stream().filter(t -> StringUtils.isNotBlank(e.getAssignee()) && t.getUserId().toString().equals(e.getAssigneeId().toString()))
                .findFirst().ifPresent(t -> {
                    e.setAssignee(t.getNickName());
                    e.setAssigneeId(t.getUserId());
                }));
        }
        //??????id??????
        List<String> businessKeyList = taskList.stream().map(TaskWaitingVo::getBusinessKey).collect(Collectors.toList());
        List<ActBusinessStatus> infoList = iActBusinessStatusService.getListInfoByBusinessKey(businessKeyList);
        if (CollectionUtil.isNotEmpty(infoList)) {
            taskList.forEach(e -> infoList.stream().filter(t -> t.getBusinessKey().equals(e.getBusinessKey()))
                .findFirst().ifPresent(e::setActBusinessStatus));
        }
        return TableDataInfo.build(page);
    }

    /**
     * @description: ????????????
     * @param: req
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2021/10/21
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completeTask(TaskCompleteBo req) {
        // 1.????????????
        Task task = taskService.createTaskQuery().taskId(req.getTaskId()).taskAssignee(getUserId().toString()).singleResult();

        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            //??????????????????
            if (ObjectUtil.isNotEmpty(task.getDelegationState()) && FlowConstant.PENDING.equals(task.getDelegationState().name())) {
                taskService.resolveTask(req.getTaskId());
                ActHiTaskInst hiTaskInst = iActHiTaskInstService.getById(task.getId());
                TaskEntity newTask = WorkFlowUtils.createNewTask(task, hiTaskInst.getStartTime());
                taskService.addComment(newTask.getId(), task.getProcessInstanceId(), req.getMessage());
                taskService.complete(newTask.getId());
                ActHiTaskInst actHiTaskInst = new ActHiTaskInst();
                actHiTaskInst.setId(task.getId());
                actHiTaskInst.setStartTime(new Date());
                iActHiTaskInstService.updateById(actHiTaskInst);
                return true;
            }
            //??????????????????
            ActProcessDefSettingVo setting = iActProcessDefSetting.getProcessDefSettingByDefId(task.getProcessDefinitionId());
            if (setting != null && !setting.getDefaultProcess()) {
                return CompleteTaskUtils.execute(req);
            }

            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
            // 2. ????????????????????????????????? ??????????????? ????????????????????????????????????
            List<ActNodeAssignee> actNodeAssignees = iActNodeAssigneeService.getInfoByProcessDefinitionId(task.getProcessDefinitionId());
            for (ActNodeAssignee actNodeAssignee : actNodeAssignees) {
                String column = actNodeAssignee.getMultipleColumn();
                String assigneeId = actNodeAssignee.getAssigneeId();
                if (actNodeAssignee.getMultiple() && actNodeAssignee.getIsShow()) {
                    List<Long> userIdList = req.getAssignees(actNodeAssignee.getMultipleColumn());
                    if (CollectionUtil.isNotEmpty(userIdList)) {
                        taskService.setVariable(task.getId(), column, userIdList);
                    }
                }
                //?????????????????????????????????????????????????????????
                if (actNodeAssignee.getMultiple() && !actNodeAssignee.getIsShow() && (StringUtils.isBlank(column) || StringUtils.isBlank(assigneeId))) {
                    throw new ServiceException("????????????" + processInstance.getProcessDefinitionKey() + "????????? ");
                }
                if (actNodeAssignee.getMultiple() && !actNodeAssignee.getIsShow()) {
                    WorkFlowUtils.settingAssignee(task, actNodeAssignee, actNodeAssignee.getMultiple());
                }
            }
            // 3. ????????????????????????
            taskService.addComment(req.getTaskId(), task.getProcessInstanceId(), req.getMessage());
            // ????????????
            if (CollectionUtil.isNotEmpty(req.getVariables())) {
                taskService.setVariables(req.getTaskId(), req.getVariables());
            }
            // ?????????????????????
            List<TaskListenerVo> handleBeforeList = null;
            // ?????????????????????
            List<TaskListenerVo> handleAfterList = null;
            ActNodeAssignee nodeEvent = actNodeAssignees.stream().filter(e -> task.getTaskDefinitionKey().equals(e.getNodeId())).findFirst().orElse(null);
            if (ObjectUtil.isNotEmpty(nodeEvent) && StringUtils.isNotBlank(nodeEvent.getTaskListener())) {
                List<TaskListenerVo> taskListenerVos = JsonUtils.parseArray(nodeEvent.getTaskListener(), TaskListenerVo.class);
                handleBeforeList = taskListenerVos.stream().filter(e -> FlowConstant.HANDLE_BEFORE.equals(e.getEventType())).collect(Collectors.toList());
                handleAfterList = taskListenerVos.stream().filter(e -> FlowConstant.HANDLE_AFTER.equals(e.getEventType())).collect(Collectors.toList());
            }
            // ???????????????
            if (CollectionUtil.isNotEmpty(handleBeforeList)) {
                for (TaskListenerVo taskListenerVo : handleBeforeList) {
                    WorkFlowUtils.springInvokeMethod(taskListenerVo.getBeanName(), FlowConstant.HANDLE_PROCESS
                        , task.getProcessInstanceId(), task.getId());
                }
            }
            // 4. ????????????
            taskService.complete(req.getTaskId());
            // 5. ????????????????????????????????????
            WorkFlowUtils.recordExecuteNode(task, actNodeAssignees);
            // ?????????????????????????????????
            iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.WAITING, task.getProcessInstanceId());
            // 6. ?????????????????????
            List<Task> taskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
            // 7. ???????????? ????????????
            boolean end = false;
            if (CollectionUtil.isEmpty(taskList)) {
                // ??????????????????????????? ????????????
                end = iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.FINISH, processInstance.getProcessInstanceId());
            }
            // ???????????????
            if (CollectionUtil.isNotEmpty(handleAfterList)) {
                for (TaskListenerVo taskListenerVo : handleAfterList) {
                    WorkFlowUtils.springInvokeMethod(taskListenerVo.getBeanName(), FlowConstant.HANDLE_PROCESS
                        , task.getProcessInstanceId());
                }
            }
            if (CollectionUtil.isEmpty(taskList) && end) {
                return true;
            }
            // ??????
            if (req.getIsCopy()) {
                if (StringUtils.isBlank(req.getAssigneeIds())) {
                    throw new ServiceException("????????????????????? ");
                }
                TaskEntity newTask = WorkFlowUtils.createNewTask(task, new Date());
                taskService.addComment(newTask.getId(), task.getProcessInstanceId(),
                    LoginHelper.getUsername() + "???????????????" + req.getAssigneeNames());
                taskService.complete(newTask.getId());
                WorkFlowUtils.createSubTask(taskList, req.getAssigneeIds());
            }
            // ????????????
            Boolean autoComplete = WorkFlowUtils.autoComplete(processInstance.getProcessInstanceId(), processInstance.getBusinessKey(), actNodeAssignees, req);
            if (autoComplete) {
                List<Task> nextTaskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
                if (!CollectionUtil.isEmpty(nextTaskList)) {
                    for (Task t : nextTaskList) {
                        ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> t.getTaskDefinitionKey().equals(e.getNodeId())).findFirst().orElse(null);
                        if (ObjectUtil.isNull(nodeAssignee)) {
                            throw new ServiceException("????????????" + t.getName() + "???????????????");
                        }
                        WorkFlowUtils.settingAssignee(t, nodeAssignee, nodeAssignee.getMultiple());
                    }
                } else {
                    // ??????????????????????????? ????????????
                    return iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.FINISH, processInstance.getProcessInstanceId());
                }
                // ???????????????
                WorkFlowUtils.sendMessage(req.getSendMessage(), processInstance.getProcessInstanceId());
                return true;
            }
            // 8. ??????????????? ???????????????
            List<Task> nextTaskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
            if (CollectionUtil.isEmpty(nextTaskList)) {
                // ??????????????????????????? ????????????
                return iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.FINISH, processInstance.getProcessInstanceId());
            }
            for (Task t : nextTaskList) {
                ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> t.getTaskDefinitionKey().equals(e.getNodeId())).findFirst().orElse(null);
                if (ObjectUtil.isNull(nodeAssignee)) {
                    throw new ServiceException("????????????" + t.getName() + "???????????????");
                }
                // ?????????????????????
                if (!nodeAssignee.getIsShow() && StringUtils.isBlank(t.getAssignee()) && !nodeAssignee.getMultiple()) {
                    // ????????????
                    WorkFlowUtils.settingAssignee(t, nodeAssignee, false);
                } else if (nodeAssignee.getIsShow() && StringUtils.isBlank(t.getAssignee()) && !nodeAssignee.getMultiple()) {
                    // ???????????? ????????????????????????id???????????????
                    List<Long> assignees = req.getAssignees(t.getTaskDefinitionKey());
                    if (CollectionUtil.isEmpty(assignees)) {
                        throw new ServiceException("???" + t.getName() + "?????????????????????????????????");
                    }
                    // ????????????
                    WorkFlowUtils.setAssignee(t, assignees);
                }
            }
            // ???????????????
            WorkFlowUtils.sendMessage(req.getSendMessage(), processInstance.getProcessInstanceId());

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("????????????:" + e.getMessage());
            iActBusinessStatusService.deleteCache(task.getProcessInstanceId());
            throw new ServiceException("????????????:" + e.getMessage());
        }
    }

    /**
     * @description: ????????????
     * @param: fileList
     * @param: taskId
     * @param: processInstanceId
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/9/25 11:39
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean attachmentUpload(MultipartFile[] fileList, String taskId, String processInstanceId) {
        List<Attachment> taskAttachments = taskService.getTaskAttachments(taskId);
        if (CollectionUtil.isNotEmpty(taskAttachments)) {
            for (Attachment taskAttachment : taskAttachments) {
                taskService.deleteAttachment(taskAttachment.getId());
            }
        }
        AttachmentCmd attachmentCmd = new AttachmentCmd(fileList, taskId, processInstanceId);
        return managementService.executeCommand(attachmentCmd);
    }

    /**
     * @description: ????????????
     * @param: attachmentId
     * @param: response
     * @return: void
     * @author: gssong
     * @date: 2022/9/25 15:26
     */
    @Override
    public void downloadAttachment(String attachmentId, HttpServletResponse response) {
        Attachment attachment = taskService.getAttachment(attachmentId);
        InputStream inputStream = taskService.getAttachmentContent(attachmentId);
        if (inputStream != null && attachment != null) {
            ServletOutputStream outputStream;
            try {
                outputStream = response.getOutputStream();
                byte[] bytes = IOUtils.toByteArray(inputStream);
                outputStream.write(bytes);
                outputStream.flush();
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
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
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.TaskFinishVo>
     * @author: gssong
     * @date: 2021/10/23
     */
    @Override
    public TableDataInfo<TaskFinishVo> getTaskFinishByPage(TaskBo req) {
        //???????????????
        String username = LoginHelper.getUserId().toString();
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .taskAssignee(username).finished().orderByHistoricTaskInstanceStartTime().asc();
        if (StringUtils.isNotBlank(req.getTaskName())) {
            query.taskNameLike(req.getTaskName());
        }
        List<HistoricTaskInstance> list = query.listPage(req.getPageNum(), req.getPageSize());
        long total = query.count();
        List<TaskFinishVo> taskFinishVoList = new ArrayList<>();
        for (HistoricTaskInstance hti : list) {
            TaskFinishVo taskFinishVo = new TaskFinishVo();
            BeanUtils.copyProperties(hti, taskFinishVo);
            ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(hti.getProcessDefinitionId()).singleResult();
            taskFinishVo.setProcessDefinitionName(processDefinition.getName());
            taskFinishVo.setProcessDefinitionKey(processDefinition.getKey());
            taskFinishVo.setVersion(processDefinition.getVersion());
            taskFinishVo.setAssigneeId(StringUtils.isNotBlank(hti.getAssignee()) ? Long.valueOf(hti.getAssignee()) : null);
            taskFinishVoList.add(taskFinishVo);
        }
        if (CollectionUtil.isNotEmpty(list)) {
            //???????????????
            List<Long> assigneeList = taskFinishVoList.stream().map(TaskFinishVo::getAssigneeId).collect(Collectors.toList());
            if (CollectionUtil.isNotEmpty(assigneeList)) {
                List<SysUser> userList = iUserService.selectListUserByIds(assigneeList);
                if (CollectionUtil.isNotEmpty(userList)) {
                    taskFinishVoList.forEach(e -> userList.stream().filter(t -> t.getUserId().toString().equals(e.getAssigneeId().toString())).findFirst().ifPresent(t -> e.setAssignee(t.getNickName())));
                }
            }
        }
        return new TableDataInfo<>(taskFinishVoList, total);
    }


    /**
     * @description: ???????????????????????????????????????
     * @param: req
     * @return: java.util.Map<java.lang.String, java.lang.Object>
     * @author: gssong
     * @date: 2021/10/23
     */
    @Override
    public Map<String, Object> getNextNodeInfo(NextNodeBo req) {
        Map<String, Object> map = new HashMap<>(16);
        TaskEntity task = (TaskEntity) taskService.createTaskQuery().taskId(req.getTaskId()).singleResult();
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        ActNodeAssignee nodeAssignee = iActNodeAssigneeService.getInfo(task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        //??????????????????
        List<ActTaskNode> taskNodeList = iActTaskNodeService.getListByInstanceId(task.getProcessInstanceId()).stream().filter(ActTaskNode::getIsBack).collect(Collectors.toList());
        map.put("backNodeList", taskNodeList);
        //????????????????????????
        ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByProcessInstId(task.getProcessInstanceId());
        if (!ObjectUtil.isEmpty(actBusinessStatus)) {
            map.put("businessStatus", actBusinessStatus);
        }
        //????????????
        if (ObjectUtil.isNotEmpty(task.getDelegationState()) && FlowConstant.PENDING.equals(task.getDelegationState().name())) {
            ActNodeAssignee actNodeAssignee = new ActNodeAssignee();
            actNodeAssignee.setIsDelegate(false);
            actNodeAssignee.setIsTransmit(false);
            actNodeAssignee.setIsCopy(false);
            actNodeAssignee.setAddMultiInstance(false);
            actNodeAssignee.setDeleteMultiInstance(false);
            map.put("setting", actNodeAssignee);
            map.put("list", new ArrayList<>());
            map.put("isMultiInstance", false);
            return map;
        }
        //??????????????????
        if (ObjectUtil.isNotEmpty(nodeAssignee)) {
            map.put("setting", nodeAssignee);
        } else {
            ActNodeAssignee actNodeAssignee = new ActNodeAssignee();
            actNodeAssignee.setIsDelegate(false);
            actNodeAssignee.setIsTransmit(false);
            actNodeAssignee.setIsCopy(false);
            actNodeAssignee.setAddMultiInstance(false);
            actNodeAssignee.setDeleteMultiInstance(false);
            map.put("setting", actNodeAssignee);
        }

        //???????????????????????????
        MultiVo isMultiInstance = WorkFlowUtils.isMultiInstance(task.getProcessDefinitionId(), task.getTaskDefinitionKey());
        map.put("isMultiInstance", ObjectUtil.isNotEmpty(isMultiInstance));
        //????????????
        List<Task> taskList = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
        //?????????????????????
        if (ObjectUtil.isNotEmpty(isMultiInstance)) {
            if (isMultiInstance.getType() instanceof ParallelMultiInstanceBehavior) {
                map.put("multiList", multiList(task, taskList, isMultiInstance.getType(), null));
            } else if (isMultiInstance.getType() instanceof SequentialMultiInstanceBehavior) {
                List<Long> assigneeList = (List<Long>) runtimeService.getVariable(task.getExecutionId(), isMultiInstance.getAssigneeList());
                map.put("multiList", multiList(task, taskList, isMultiInstance.getType(), assigneeList));
            }
        } else {
            map.put("multiList", new ArrayList<>());
        }
        //?????????????????????????????????????????????
        if (CollectionUtil.isNotEmpty(taskList) && taskList.size() > 1) {
            //return null;
        }

        if (CollectionUtil.isNotEmpty(req.getVariables())) {
            taskService.setVariables(task.getId(), req.getVariables());
        }
        //????????????
        String processDefinitionId = task.getProcessDefinitionId();
        //??????bpmn??????
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        //??????????????????id??????????????????????????????
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        //????????????
        Collection<FlowElement> flowElements = bpmnModel.getProcesses().get(0).getFlowElements();
        //???????????????????????????????????????
        List<ProcessNode> nextNodeList = new ArrayList<>();
        //??????????????????????????????
        List<ProcessNode> tempNodeList = new ArrayList<>();
        ExecutionEntityImpl executionEntity = (ExecutionEntityImpl) runtimeService.createExecutionQuery()
            .executionId(task.getExecutionId()).singleResult();
        WorkFlowUtils.getNextNodeList(flowElements, flowElement, executionEntity, nextNodeList, tempNodeList, task.getId(), null);
        if (CollectionUtil.isNotEmpty(nextNodeList)) {
            nextNodeList.removeIf(node -> !node.getExpression());
        }
        if (CollectionUtil.isNotEmpty(nextNodeList) && CollectionUtil.isNotEmpty(nextNodeList.stream().filter(e -> e.getExpression() != null && e.getExpression()).collect(Collectors.toList()))) {
            List<ProcessNode> nodeList = nextNodeList.stream().filter(e -> e.getExpression() != null && e.getExpression()).collect(Collectors.toList());
            List<ProcessNode> processNodeList = getProcessNodeAssigneeList(nodeList, task.getProcessDefinitionId());
            map.put("list", processNodeList);
        } else if (CollectionUtil.isNotEmpty(tempNodeList)) {
            List<ProcessNode> processNodeList = getProcessNodeAssigneeList(tempNodeList, task.getProcessDefinitionId());
            map.put("list", processNodeList);
        } else {
            map.put("list", nextNodeList);
        }
        map.put("processInstanceId", task.getProcessInstanceId());
        //??????????????????
        ActProcessDefSettingVo setting = iActProcessDefSetting.getProcessDefSettingByDefId(task.getProcessDefinitionId());
        if (setting != null && !setting.getDefaultProcess()) {
            Map<String, Object> executableNode = iActProcessInstanceService.getExecutableNode(task.getProcessInstanceId());
            map.putAll(executableNode);
            map.put("defaultProcess", true);
            map.put("list", Collections.emptyList());
            if (BusinessStatusEnum.WAITING.getStatus().equals(actBusinessStatus.getStatus())) {
                map.put("processNodeList", Collections.emptyList());
            }
        } else {
            map.put("defaultProcess", false);
            map.put("processNodeList", Collections.emptyList());
        }
        return map;
    }


    /**
     * @description: ?????????????????????
     * @param: task  ????????????
     * @param: taskList  ????????????????????????
     * @param: type  ????????????
     * @param: assigneeList ??????????????????
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.TaskVo>
     * @author: gssong
     * @date: 2022/4/24 11:17
     */
    private List<TaskVo> multiList(TaskEntity task, List<Task> taskList, Object type, List<Long> assigneeList) {
        List<TaskVo> taskListVo = new ArrayList<>();
        if (type instanceof SequentialMultiInstanceBehavior) {
            List<Long> userIds = assigneeList.stream().filter(userId -> !userId.toString().equals(task.getAssignee())).collect(Collectors.toList());
            List<SysUser> sysUsers = null;
            if (CollectionUtil.isNotEmpty(userIds)) {
                sysUsers = iUserService.selectListUserByIds(userIds);
            }
            for (Long userId : userIds) {
                TaskVo taskVo = new TaskVo();
                taskVo.setId("????????????");
                taskVo.setExecutionId("????????????");
                taskVo.setProcessInstanceId(task.getProcessInstanceId());
                taskVo.setName(task.getName());
                taskVo.setAssigneeId(String.valueOf(userId));
                if (CollectionUtil.isNotEmpty(sysUsers) && sysUsers != null) {
                    sysUsers.stream().filter(u -> u.getUserId().toString().equals(userId.toString())).findFirst().ifPresent(user -> taskVo.setAssignee(user.getNickName()));
                }
                taskListVo.add(taskVo);
            }
            return taskListVo;
        } else if (type instanceof ParallelMultiInstanceBehavior) {
            List<Task> tasks = taskList.stream().filter(e -> StringUtils.isBlank(e.getParentTaskId()) && !e.getExecutionId().equals(task.getExecutionId())
                && e.getTaskDefinitionKey().equals(task.getTaskDefinitionKey())).collect(Collectors.toList());
            if (CollectionUtil.isNotEmpty(tasks)) {
                List<Long> userIds = tasks.stream().map(e -> Long.valueOf(e.getAssignee())).collect(Collectors.toList());
                List<SysUser> sysUsers = null;
                if (CollectionUtil.isNotEmpty(userIds)) {
                    sysUsers = iUserService.selectListUserByIds(userIds);
                }
                for (Task t : tasks) {
                    TaskVo taskVo = new TaskVo();
                    taskVo.setId(t.getId());
                    taskVo.setExecutionId(t.getExecutionId());
                    taskVo.setProcessInstanceId(t.getProcessInstanceId());
                    taskVo.setName(t.getName());
                    taskVo.setAssigneeId(t.getAssignee());
                    if (CollectionUtil.isNotEmpty(sysUsers)) {
                        SysUser sysUser = sysUsers.stream().filter(u -> u.getUserId().toString().equals(t.getAssignee())).findFirst().orElse(null);
                        if (ObjectUtil.isNotEmpty(sysUser)) {
                            taskVo.setAssignee(sysUser.getNickName());
                        }
                    }
                    taskListVo.add(taskVo);
                }
                return taskListVo;
            }
        }
        return Collections.emptyList();
    }

    /**
     * @description: ????????????????????????
     * @param: nodeList????????????
     * @param: definitionId ????????????id
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.ProcessNode>
     * @author: gssong
     * @date: 2021/10/23
     */
    private List<ProcessNode> getProcessNodeAssigneeList(List<ProcessNode> nodeList, String definitionId) {
        List<ActNodeAssignee> actNodeAssignees = iActNodeAssigneeService.getInfoByProcessDefinitionId(definitionId);
        if (CollUtil.isEmpty(actNodeAssignees)) {
            throw new ServiceException("????????????????????????????????????????????????????????????");
        }
        for (ProcessNode processNode : nodeList) {
            if (CollectionUtil.isEmpty(actNodeAssignees)) {
                throw new ServiceException("????????????????????????????????????????????????");
            }
            ActNodeAssignee nodeAssignee = actNodeAssignees.stream().filter(e -> e.getNodeId().equals(processNode.getNodeId())).findFirst().orElse(null);

            //????????? ?????? ??????id ???????????????????????????
            if (ObjectUtil.isNotNull(nodeAssignee) && StringUtils.isNotBlank(nodeAssignee.getAssigneeId())
                && nodeAssignee.getBusinessRuleId() == null && StringUtils.isNotBlank(nodeAssignee.getAssignee())) {
                processNode.setChooseWay(nodeAssignee.getChooseWay());
                processNode.setAssignee(nodeAssignee.getAssignee());
                processNode.setAssigneeId(nodeAssignee.getAssigneeId());
                processNode.setIsShow(nodeAssignee.getIsShow());
                if (nodeAssignee.getMultiple()) {
                    processNode.setNodeId(nodeAssignee.getMultipleColumn());
                }
                processNode.setMultiple(nodeAssignee.getMultiple());
                processNode.setMultipleColumn(nodeAssignee.getMultipleColumn());
                //??????????????????????????????????????????
            } else if (ObjectUtil.isNotNull(nodeAssignee) && nodeAssignee.getBusinessRuleId() != null) {
                ActBusinessRuleVo actBusinessRuleVo = iActBusinessRuleService.queryById(nodeAssignee.getBusinessRuleId());
                List<String> ruleAssignList = WorkFlowUtils.ruleAssignList(actBusinessRuleVo, processNode.getTaskId(), processNode.getNodeName());
                processNode.setChooseWay(nodeAssignee.getChooseWay());
                processNode.setAssignee(StrUtil.EMPTY);
                processNode.setAssigneeId(String.join(",", ruleAssignList));
                processNode.setIsShow(nodeAssignee.getIsShow());
                processNode.setBusinessRuleId(nodeAssignee.getBusinessRuleId());
                if (nodeAssignee.getMultiple()) {
                    processNode.setNodeId(nodeAssignee.getMultipleColumn());
                }
                processNode.setMultiple(nodeAssignee.getMultiple());
                processNode.setMultipleColumn(nodeAssignee.getMultipleColumn());
            } else {
                throw new ServiceException(processNode.getNodeName() + "??????????????????????????????????????????");
            }
        }
        if (CollectionUtil.isNotEmpty(nodeList)) {
            // ????????????????????????????????????
            nodeList.removeIf(node -> !node.getIsShow());
        }
        return nodeList;
    }

    /**
     * @description: ?????????????????????????????????
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.TaskFinishVo>
     * @author: gssong
     * @date: 2021/10/23
     */
    @Override
    public TableDataInfo<TaskFinishVo> getAllTaskFinishByPage(TaskBo req) {
        HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
            .finished().orderByHistoricTaskInstanceStartTime().asc();
        if (StringUtils.isNotBlank(req.getTaskName())) {
            query.taskNameLike(req.getTaskName());
        }
        List<HistoricTaskInstance> list = query.listPage(req.getPageNum(), req.getPageSize());
        long total = query.count();
        List<TaskFinishVo> taskFinishVoList = new ArrayList<>();
        for (HistoricTaskInstance hti : list) {
            TaskFinishVo taskFinishVo = new TaskFinishVo();
            BeanUtils.copyProperties(hti, taskFinishVo);
            ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(hti.getProcessDefinitionId()).singleResult();
            taskFinishVo.setProcessDefinitionName(processDefinition.getName());
            taskFinishVo.setProcessDefinitionKey(processDefinition.getKey());
            taskFinishVo.setVersion(processDefinition.getVersion());
            taskFinishVo.setAssigneeId(StringUtils.isNotBlank(hti.getAssignee()) ? Long.valueOf(hti.getAssignee()) : null);
            taskFinishVoList.add(taskFinishVo);
        }
        if (CollectionUtil.isNotEmpty(list)) {
            //???????????????
            List<Long> assigneeList = taskFinishVoList.stream().map(TaskFinishVo::getAssigneeId).collect(Collectors.toList());
            if (CollectionUtil.isNotEmpty(assigneeList)) {
                List<SysUser> userList = iUserService.selectListUserByIds(assigneeList);
                if (CollectionUtil.isNotEmpty(userList)) {
                    taskFinishVoList.forEach(e -> userList.stream().filter(t -> t.getUserId().compareTo(e.getAssigneeId()) == 0).findFirst().ifPresent(u -> {
                        e.setAssignee(u.getNickName());
                        e.setAssigneeId(u.getUserId());
                    }));
                }
            }
        }
        return new TableDataInfo<>(taskFinishVoList, total);
    }

    /**
     * @description: ?????????????????????????????????
     * @param: req
     * @return: com.ruoyi.common.core.page.TableDataInfo<com.ruoyi.workflow.domain.vo.TaskWaitingVo>
     * @author: gssong
     * @date: 2021/10/17
     */
    @Override
    public TableDataInfo<TaskWaitingVo> getAllTaskWaitByPage(TaskBo req) {
        TaskQuery query = taskService.createTaskQuery()
            .orderByTaskCreateTime().asc();
        if (StringUtils.isNotEmpty(req.getTaskName())) {
            query.taskNameLikeIgnoreCase("%" + req.getTaskName() + "%");
        }
        if (StringUtils.isNotEmpty(req.getProcessDefinitionName())) {
            query.processDefinitionNameLike("%" + req.getProcessDefinitionName() + "%");
        }
        List<Task> taskList = query.listPage(req.getPageNum(), req.getPageSize());
        if (CollectionUtil.isEmpty(taskList)) {
            return new TableDataInfo<>();
        }
        long total = query.count();
        List<TaskWaitingVo> list = new ArrayList<>();
        //????????????id
        Set<String> processInstanceIds = taskList.stream().map(Task::getProcessInstanceId).collect(Collectors.toSet());
        //????????????id
        List<String> processDefinitionIds = taskList.stream().map(Task::getProcessDefinitionId).collect(Collectors.toList());
        //??????????????????
        List<ProcessInstance> processInstanceList = runtimeService.createProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
        //????????????????????????
        List<ActProcessDefSettingVo> processDefSettingLists = iActProcessDefSetting.getProcessDefSettingByDefIds(processDefinitionIds);
        //?????????
        List<Long> assignees = taskList.stream().filter(e -> StringUtils.isNotBlank(e.getAssignee())).map(e -> Long.valueOf(e.getAssignee())).filter(ObjectUtil::isNotEmpty).collect(Collectors.toList());
        //???????????????
        List<Long> userIds = processInstanceList.stream().map(e -> Long.valueOf(e.getStartUserId())).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(assignees)) {
            userIds.addAll(assignees);
        }
        List<SysUser> userList = iUserService.selectListUserByIds(userIds);
        //????????????
        List<Task> taskCollect = taskService.createTaskQuery().processInstanceIdIn(processInstanceIds).list();
        for (Task task : taskList) {
            TaskWaitingVo taskWaitingVo = new TaskWaitingVo();
            BeanUtils.copyProperties(task, taskWaitingVo);
            taskWaitingVo.setAssigneeId(StringUtils.isNotBlank(task.getAssignee()) ? Long.valueOf(task.getAssignee()) : null);
            taskWaitingVo.setSuspensionState(task.isSuspended());
            taskWaitingVo.setProcessStatus(!task.isSuspended() ? "??????" : "??????");
            processInstanceList.stream().filter(e -> e.getProcessInstanceId().equals(task.getProcessInstanceId())).findFirst()
                .ifPresent(e -> {
                    //???????????????
                    String startUserId = e.getStartUserId();
                    taskWaitingVo.setStartUserId(startUserId);
                    if (StringUtils.isNotBlank(startUserId)) {
                        userList.stream().filter(u -> u.getUserId().toString().equals(startUserId)).findFirst().ifPresent(u -> {
                            taskWaitingVo.setStartUserNickName(u.getNickName());
                        });
                    }
                    taskWaitingVo.setProcessDefinitionVersion(e.getProcessDefinitionVersion());
                    taskWaitingVo.setProcessDefinitionName(e.getProcessDefinitionName());
                    taskWaitingVo.setBusinessKey(e.getBusinessKey());
                });
            // ????????????????????????
            processDefSettingLists.stream().filter(e -> e.getProcessDefinitionId().equals(task.getProcessDefinitionId())).findFirst()
                .ifPresent(taskWaitingVo::setActProcessDefSetting);
            //????????????
            MultiVo multiInstance = WorkFlowUtils.isMultiInstance(task.getProcessDefinitionId(), task.getTaskDefinitionKey());
            taskWaitingVo.setMultiInstance(ObjectUtil.isNotEmpty(multiInstance));
            //????????????
            List<Task> tasks = taskCollect.stream().filter(e -> e.getProcessInstanceId().equals(task.getProcessInstanceId())).collect(Collectors.toList());
            //?????????????????????
            if (ObjectUtil.isNotEmpty(multiInstance)) {
                if (multiInstance.getType() instanceof ParallelMultiInstanceBehavior) {
                    taskWaitingVo.setTaskVoList(multiList((TaskEntity) task, tasks, multiInstance.getType(), null));
                } else if (multiInstance.getType() instanceof SequentialMultiInstanceBehavior && StringUtils.isNotBlank(task.getExecutionId())) {
                    List<Long> assigneeList = (List<Long>) runtimeService.getVariable(task.getExecutionId(), multiInstance.getAssigneeList());
                    taskWaitingVo.setTaskVoList(multiList((TaskEntity) task, tasks, multiInstance.getType(), assigneeList));
                }
            }
            list.add(taskWaitingVo);
        }
        if (CollectionUtil.isNotEmpty(list)) {
            //?????????????????????
            list.forEach(e -> {
                List<IdentityLink> identityLinkList = WorkFlowUtils.getCandidateUser(e.getId());
                if (CollectionUtil.isNotEmpty(identityLinkList)) {
                    List<String> collectType = identityLinkList.stream().map(IdentityLink::getType).collect(Collectors.toList());
                    if (StringUtils.isBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                        e.setIsClaim(false);
                    } else if (StringUtils.isNotBlank(e.getAssignee()) && collectType.size() > 1 && collectType.contains(FlowConstant.CANDIDATE)) {
                        e.setIsClaim(true);
                    }
                }
            });
            //???????????????
            if (CollectionUtil.isNotEmpty(userList)) {
                list.forEach(e -> userList.stream().filter(t -> StringUtils.isNotBlank(e.getAssignee()) && t.getUserId().toString().equals(e.getAssigneeId().toString()))
                    .findFirst().ifPresent(t -> {
                        e.setAssignee(t.getNickName());
                        e.setAssigneeId(t.getUserId());
                    }));
            }
            //??????id??????
            List<String> businessKeyList = list.stream().map(TaskWaitingVo::getBusinessKey).collect(Collectors.toList());
            List<ActBusinessStatus> infoList = iActBusinessStatusService.getListInfoByBusinessKey(businessKeyList);
            if (CollectionUtil.isNotEmpty(infoList)) {
                list.forEach(e -> infoList.stream().filter(t -> t.getBusinessKey().equals(e.getBusinessKey())).findFirst().ifPresent(e::setActBusinessStatus));
            }
        }
        return new TableDataInfo<>(list, total);
    }

    /**
     * @description: ????????????
     * @param: backProcessBo
     * @return: java.lang.String
     * @author: gssong
     * @date: 2021/11/6
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String backProcess(BackProcessBo backProcessBo) {

        Task task = taskService.createTaskQuery().taskId(backProcessBo.getTaskId()).taskAssignee(getUserId().toString()).singleResult();
        String processInstanceId = task.getProcessInstanceId();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            //???????????????????????????
            List<Task> taskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            List<String> gatewayNode = WorkFlowUtils.getGatewayNode(task, backProcessBo.getTargetActivityId());
            //?????????????????????????????????(??????)??????
            taskService.addComment(task.getId(), processInstanceId, StringUtils.isNotBlank(backProcessBo.getComment()) ? backProcessBo.getComment() : "??????");
            if (CollectionUtil.isNotEmpty(gatewayNode) && taskList.size() == 1) {
                runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveSingleActivityIdToActivityIds(taskList.get(0).getTaskDefinitionKey(), gatewayNode)
                    .changeState();
                //???????????????????????????????????????
            } else if (taskList.size() > 1 && CollectionUtil.isEmpty(gatewayNode)) {
                runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveActivityIdsToSingleActivityId(taskList.stream().map(Task::getTaskDefinitionKey).distinct().collect(Collectors.toList()), backProcessBo.getTargetActivityId())
                    .changeState();
                //????????????????????????????????????
            } else if (taskList.size() == 1 && CollectionUtil.isEmpty(gatewayNode)) {
                runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                    .moveActivityIdTo(taskList.get(0).getTaskDefinitionKey(), backProcessBo.getTargetActivityId())
                    .changeState();
                //?????????????????????????????????(??????)??????
            } else if (taskList.size() > 1 && CollectionUtil.isNotEmpty(gatewayNode)) {
                taskList.forEach(e -> {
                    if (e.getId().equals(backProcessBo.getTaskId())) {
                        runtimeService.createChangeActivityStateBuilder().processInstanceId(processInstanceId)
                            .moveSingleActivityIdToActivityIds(e.getTaskDefinitionKey(), gatewayNode)
                            .changeState();
                    } else {
                        WorkFlowUtils.deleteRuntimeTask(e);
                    }
                });
            } else {
                throw new ServiceException("????????????");
            }
            List<Task> otherTasks = null;
            if (taskList.size() > 1) {
                otherTasks = taskList.stream().filter(e -> !e.getId().equals(backProcessBo.getTaskId())).collect(Collectors.toList());
            }
            if (CollectionUtil.isNotEmpty(otherTasks)) {
                otherTasks.forEach(e -> historyService.deleteHistoricTaskInstance(e.getId()));
            }

            List<Task> newTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            //????????????????????????????????????
            if (CollectionUtil.isNotEmpty(newTaskList)) {
                List<Task> taskCollect = newTaskList.stream().filter(e -> e.getTaskDefinitionKey().equals(backProcessBo.getTargetActivityId())).collect(Collectors.toList());
                if (taskCollect.size() > 1) {
                    taskCollect.remove(0);
                    taskCollect.forEach(WorkFlowUtils::deleteRuntimeTask);
                }
            }
            ActTaskNode actTaskNode = iActTaskNodeService.getListByInstanceIdAndNodeId(task.getProcessInstanceId(), backProcessBo.getTargetActivityId());

            if (ObjectUtil.isNotEmpty(actTaskNode) && FlowConstant.USER_TASK.equals(actTaskNode.getTaskType())) {
                List<Task> runTaskList = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
                for (Task runTask : runTaskList) {
                    //???????????????????????????
                    List<HistoricTaskInstance> oldTargetTaskList = historyService.createHistoricTaskInstanceQuery()
                        // ??????id
                        .taskDefinitionKey(runTask.getTaskDefinitionKey())
                        .processInstanceId(processInstanceId)
                        //????????????????????????
                        .finished()
                        //???????????????????????????
                        .orderByTaskCreateTime().desc()
                        .list();
                    if (CollectionUtil.isNotEmpty(oldTargetTaskList)) {
                        HistoricTaskInstance oldTargetTask = oldTargetTaskList.get(0);
                        taskService.setAssignee(runTask.getId(), oldTargetTask.getAssignee());
                    }

                }
            }

            //??????????????????????????????
            if (ObjectUtil.isNotNull(actTaskNode) && actTaskNode.getOrderNo() == 0) {
                ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
                iActBusinessStatusService.updateState(processInstance.getBusinessKey(), BusinessStatusEnum.BACK, processInstanceId);
            }
            iActTaskNodeService.deleteBackTaskNode(processInstanceId, backProcessBo.getTargetActivityId());
            //???????????????
            WorkFlowUtils.sendMessage(backProcessBo.getSendMessage(), processInstanceId);
            return processInstanceId;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException("????????????:" + e.getMessage());
        }
    }

    /**
     * @description: ?????????????????????????????????????????????
     * @param: processInstId
     * @return: java.util.List<com.ruoyi.workflow.domain.ActTaskNode>
     * @author: gssong
     * @date: 2021/11/6
     */
    @Override
    public List<ActTaskNode> getBackNodes(String processInstId) {
        return iActTaskNodeService.getListByInstanceId(processInstId).stream().filter(ActTaskNode::getIsBack).collect(Collectors.toList());
    }

    /**
     * @description: ????????????
     * @param: taskREQ
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/3/4
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delegateTask(DelegateBo delegateBo) {
        if (StringUtils.isBlank(delegateBo.getDelegateUserId())) {
            throw new ServiceException("??????????????????");
        }
        TaskEntity task = (TaskEntity) taskService.createTaskQuery().taskId(delegateBo.getTaskId())
            .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            TaskEntity newTask = WorkFlowUtils.createNewTask(task, new Date());
            taskService.addComment(newTask.getId(), task.getProcessInstanceId(), "???" + LoginHelper.getUsername() + "???????????????" + delegateBo.getDelegateUserName() + "???");
            //????????????
            taskService.delegateTask(delegateBo.getTaskId(), delegateBo.getDelegateUserId());
            //???????????????????????????
            taskService.complete(newTask.getId());
            ActHiTaskInst actHiTaskInst = new ActHiTaskInst();
            actHiTaskInst.setId(task.getId());
            actHiTaskInst.setStartTime(new Date());
            iActHiTaskInstService.updateById(actHiTaskInst);
            //???????????????
            WorkFlowUtils.sendMessage(delegateBo.getSendMessage(), task.getProcessInstanceId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ????????????
     * @param: transmitBo
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/3/13 13:18
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean transmitTask(TransmitBo transmitBo) {
        Task task = taskService.createTaskQuery().taskId(transmitBo.getTaskId())
            .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        if (ObjectUtil.isEmpty(task)) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        try {
            TaskEntity newTask = WorkFlowUtils.createNewTask(task, new Date());
            taskService.addComment(newTask.getId(), task.getProcessInstanceId(),
                StringUtils.isNotBlank(transmitBo.getComment()) ? transmitBo.getComment() : LoginHelper.getUsername() + "???????????????");
            taskService.complete(newTask.getId());
            taskService.setAssignee(task.getId(), transmitBo.getTransmitUserId());
            //???????????????
            WorkFlowUtils.sendMessage(transmitBo.getSendMessage(), task.getProcessInstanceId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ??????????????????
     * @param: addMultiBo
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/4/15 13:06
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addMultiInstanceExecution(AddMultiBo addMultiBo) {
        Task task;
        if (LoginHelper.isAdmin()) {
            task = taskService.createTaskQuery().taskId(addMultiBo.getTaskId()).singleResult();
        } else {
            task = taskService.createTaskQuery().taskId(addMultiBo.getTaskId())
                .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        }
        if (ObjectUtil.isEmpty(task) && !LoginHelper.isAdmin()) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();
        MultiVo multiVo = WorkFlowUtils.isMultiInstance(processDefinitionId, taskDefinitionKey);
        if (ObjectUtil.isEmpty(multiVo)) {
            throw new ServiceException("??????????????????????????????");
        }
        try {
            if (multiVo.getType() instanceof ParallelMultiInstanceBehavior) {
                for (Long assignee : addMultiBo.getAssignees()) {
                    runtimeService.addMultiInstanceExecution(taskDefinitionKey, processInstanceId, Collections.singletonMap(multiVo.getAssignee(), assignee));
                }
            } else if (multiVo.getType() instanceof SequentialMultiInstanceBehavior) {
                AddSequenceMultiInstanceCmd addSequenceMultiInstanceCmd = new AddSequenceMultiInstanceCmd(task.getExecutionId(), multiVo.getAssigneeList(), addMultiBo.getAssignees());
                managementService.executeCommand(addSequenceMultiInstanceCmd);
            }
            List<String> assigneeNames = addMultiBo.getAssigneeNames();
            String username = LoginHelper.getUsername();
            TaskEntity newTask = WorkFlowUtils.createNewTask(task, new Date());
            taskService.addComment(newTask.getId(), processInstanceId, username + "?????????" + String.join(",", assigneeNames) + "???");
            taskService.complete(newTask.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ??????????????????
     * @param: deleteMultiBo
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/4/16 10:59
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteMultiInstanceExecution(DeleteMultiBo deleteMultiBo) {
        Task task;
        if (LoginHelper.isAdmin()) {
            task = taskService.createTaskQuery().taskId(deleteMultiBo.getTaskId()).singleResult();
        } else {
            task = taskService.createTaskQuery().taskId(deleteMultiBo.getTaskId())
                .taskCandidateOrAssigned(LoginHelper.getUserId().toString()).singleResult();
        }
        if (ObjectUtil.isEmpty(task) && !LoginHelper.isAdmin()) {
            throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
        }
        if (task.isSuspended()) {
            throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
        }
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();
        MultiVo multiVo = WorkFlowUtils.isMultiInstance(processDefinitionId, taskDefinitionKey);
        if (ObjectUtil.isEmpty(multiVo)) {
            throw new ServiceException("??????????????????????????????");
        }
        try {
            if (multiVo.getType() instanceof ParallelMultiInstanceBehavior) {
                for (String executionId : deleteMultiBo.getExecutionIds()) {
                    runtimeService.deleteMultiInstanceExecution(executionId, false);
                }
                for (String taskId : deleteMultiBo.getTaskIds()) {
                    historyService.deleteHistoricTaskInstance(taskId);
                }
            } else if (multiVo.getType() instanceof SequentialMultiInstanceBehavior) {
                DeleteSequenceMultiInstanceCmd deleteSequenceMultiInstanceCmd = new DeleteSequenceMultiInstanceCmd(task.getAssignee(), task.getExecutionId(), multiVo.getAssigneeList(), deleteMultiBo.getAssigneeIds());
                managementService.executeCommand(deleteSequenceMultiInstanceCmd);
            }
            List<String> assigneeNames = deleteMultiBo.getAssigneeNames();
            String username = LoginHelper.getUsername();
            TaskEntity newTask = WorkFlowUtils.createNewTask(task, new Date());
            taskService.addComment(newTask.getId(), processInstanceId, username + "?????????" + String.join(",", assigneeNames) + "???");
            taskService.complete(newTask.getId());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ???????????????
     * @param: updateAssigneeBo
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/7/17 13:35
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateAssignee(UpdateAssigneeBo updateAssigneeBo) {
        List<Task> list = taskService.createNativeTaskQuery().sql("select * from act_ru_task where id_ in " + getInParam(updateAssigneeBo.getTaskIdList())).list();
        if (CollectionUtil.isEmpty(list)) {
            throw new ServiceException("??????????????????????????????");
        }
        try {
            for (Task task : list) {
                taskService.setAssignee(task.getId(), updateAssigneeBo.getUserId());
            }
            return true;
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ???????????????, ?????????????????????in??????.
     * @param: param
     * @return: java.lang.String
     * @author: gssong
     * @date: 2022/7/22 12:17
     */
    private String getInParam(List<String> param) {
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        for (int i = 0; i < param.size(); i++) {
            sb.append("'").append(param.get(i)).append("'");
            if (i != param.size() - 1) {
                sb.append(",");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * @description: ??????????????????
     * @param: taskId
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.VariableVo>
     * @author: gssong
     * @date: 2022/7/23 14:33
     */
    @Override
    public List<VariableVo> getProcessInstVariable(String taskId) {
        List<VariableVo> variableVoList = new ArrayList<>();
        Map<String, VariableInstance> variableInstances = taskService.getVariableInstances(taskId);
        if (CollectionUtil.isNotEmpty(variableInstances)) {
            for (Map.Entry<String, VariableInstance> entry : variableInstances.entrySet()) {
                VariableVo variableVo = new VariableVo();
                variableVo.setKey(entry.getKey());
                variableVo.setValue(entry.getValue().getValue().toString());
                variableVoList.add(variableVo);
            }
        }
        return variableVoList;
    }

    /**
     * @description: ??????????????????
     * @param: commentId
     * @param: comment
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/7/24 13:28
     */
    @Override
    public Boolean editComment(String commentId, String comment) {
        return actTaskMapper.editComment(commentId, comment) > 0;
    }

    /**
     * @description: ????????????
     * @param: fileList
     * @param: taskId
     * @param: processInstanceId
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/9/26 13:01
     */
    @Override
    public Boolean editAttachment(MultipartFile[] fileList, String taskId, String processInstanceId) {
        AttachmentCmd attachmentCmd = new AttachmentCmd(fileList, taskId, processInstanceId);
        return managementService.executeCommand(attachmentCmd);
    }

    /**
     * @description: ????????????
     * @param: attachmentId
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/9/26 13:11
     */
    @Override
    public Boolean deleteAttachment(String attachmentId) {
        try {
            taskService.deleteAttachment(attachmentId);
            return true;
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * @description: ????????????
     * @param: taskBo
     * @return: java.lang.Boolean
     * @author: gssong
     * @date: 2022/10/27 20:32
     */
    @Override
    public Boolean terminationTask(TaskBo taskBo) {
        try {
            Task task = taskService.createTaskQuery().taskId(taskBo.getTaskId()).singleResult();
            if (ObjectUtil.isEmpty(task)) {
                throw new ServiceException(FlowConstant.MESSAGE_CURRENT_TASK_IS_NULL);
            }
            if (task.isSuspended()) {
                throw new ServiceException(FlowConstant.MESSAGE_SUSPENDED);
            }
            ActBusinessStatus actBusinessStatus = iActBusinessStatusService.getInfoByProcessInstId(task.getProcessInstanceId());
            if (actBusinessStatus == null) {
                throw new ServiceException("??????????????????????????????act_business_status??????");
            }
            if (StringUtils.isBlank(taskBo.getComment())) {
                taskBo.setComment(LoginHelper.getUsername() + "???????????????");
            } else {
                taskBo.setComment(LoginHelper.getUsername() + "??????????????????" + taskBo.getComment());
            }
            taskService.addComment(task.getId(), task.getProcessInstanceId(), taskBo.getComment());
            List<Task> list = taskService.createTaskQuery().processInstanceId(task.getProcessInstanceId()).list();
            if (CollectionUtil.isNotEmpty(list)) {
                List<Task> subTasks = list.stream().filter(e -> StringUtils.isNotBlank(e.getParentTaskId())).collect(Collectors.toList());
                if (CollectionUtil.isNotEmpty(subTasks)) {
                    subTasks.forEach(e -> taskService.deleteTask(e.getId()));
                }
                runtimeService.deleteProcessInstance(task.getProcessInstanceId(), StrUtil.EMPTY);
            }
            return iActBusinessStatusService.updateState(actBusinessStatus.getBusinessKey(), BusinessStatusEnum.TERMINATION, task.getProcessInstanceId());
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }
}

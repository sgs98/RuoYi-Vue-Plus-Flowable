package com.ruoyi.workflow.utils;

import cn.hutool.core.collection.CollectionUtil;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.workflow.common.constant.FlowConstant;
import com.ruoyi.workflow.domain.vo.MultiVo;
import com.ruoyi.workflow.domain.vo.ProcessNodePath;
import com.ruoyi.workflow.flowable.cmd.ExpressCheckCmd;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.flowable.bpmn.model.*;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @description: 获取流程审批路线
 * @author: gssong
 * @date: 2022/8/22 18:40
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ProcessRunningPathUtils {

    /**
     * 流程引擎
     */
    private static final ProcessEngine PROCESS_ENGINE = SpringUtils.getBean(ProcessEngine.class);

    /**
     * @description: 获取流程审批路线
     * @param: processInstanceId 流程实例id
     * @return: java.util.List<com.ruoyi.workflow.domain.vo.ProcessNodePath>
     * @author: gssong
     * @date: 2022/8/23 19:28
     */
    public static List<ProcessNodePath> getProcessNodeList(String processInstanceId) {

        ProcessInstance processInstance = PROCESS_ENGINE.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        BpmnModel bpmnModel = PROCESS_ENGINE.getRepositoryService().getBpmnModel(processInstance.getProcessDefinitionId());
        Collection<FlowElement> flowElements = bpmnModel.getMainProcess().getFlowElements();
        List<Task> list = PROCESS_ENGINE.getTaskService().createTaskQuery().processInstanceId(processInstanceId).list();
        List<ProcessNodePath> processNodePathList = new ArrayList<>();
        Map<String, Object> variables = PROCESS_ENGINE.getRuntimeService().getVariables(list.get(0).getExecutionId());
        FlowElement startElement = flowElements.stream().filter(StartEvent.class::isInstance).findFirst().orElse(null);
        assert startElement != null;
        List<SequenceFlow> outgoingFlows = ((StartEvent) startElement).getOutgoingFlows();
        if (outgoingFlows.size() == 1) {
            ProcessNodePath processNodePath = new ProcessNodePath();
            processNodePath.setFirst(true);
            buildData(processNodePath, null, variables, outgoingFlows.get(0).getTargetFlowElement(), startElement, FlowConstant.USER_TASK, processNodePathList);
            getNextNodeList(processNodePathList, flowElements, outgoingFlows.get(0), variables, null);
        }
        Map<String, List<ProcessNodePath>> listMap = processNodePathList.stream().collect(Collectors.groupingBy(ProcessNodePath::getSourceFlowElementId));
        List<ProcessNodePath> buildList = new ArrayList<>();
        for (Map.Entry<String, List<ProcessNodePath>> exclusiveListEntry : listMap.entrySet()) {
            List<ProcessNodePath> nodeList = exclusiveListEntry.getValue();
            if (FlowConstant.EXCLUSIVE_GATEWAY.equals(nodeList.get(0).getNodeType())) {
                List<ProcessNodePath> expressionTrueList = nodeList.stream().filter(ProcessNodePath::getExpression).collect(Collectors.toList());
                if (CollectionUtil.isNotEmpty(expressionTrueList)) {
                    buildList.addAll(expressionTrueList);
                } else {
                    List<ProcessNodePath> expressionStrTrueList = nodeList.stream().filter(e -> !e.getExpressionStr()).collect(Collectors.toList());
                    buildList.addAll(expressionStrTrueList);
                }
            } else if (FlowConstant.INCLUSIVE_GATEWAY.equals(nodeList.get(0).getNodeType())) {
                List<ProcessNodePath> expressionTrueList = nodeList.stream().filter(ProcessNodePath::getExpression).collect(Collectors.toList());
                if (CollectionUtil.isNotEmpty(expressionTrueList)) {
                    buildList.addAll(expressionTrueList);
                }
                List<ProcessNodePath> expressionStrTrueList = nodeList.stream().filter(e -> !e.getExpressionStr()).collect(Collectors.toList());
                buildList.addAll(expressionStrTrueList);
            } else {
                buildList.addAll(nodeList);
            }
        }
        for (ProcessNodePath processNodePath : buildList) {
            GraphicInfo graphicInfo = bpmnModel.getGraphicInfo(processNodePath.getNodeId());
            processNodePath.setX(graphicInfo.getX());
            MultiVo multiInstance = WorkFlowUtils.isMultiInstance(list.get(0).getProcessDefinitionId(), processNodePath.getNodeId());
            if (multiInstance != null) {
                processNodePath.setMultiple(true);
                processNodePath.setMultipleColumn(multiInstance.getAssigneeList());
            } else {
                processNodePath.setMultiple(false);
            }
        }
        return buildList.stream().sorted(Comparator.comparing(ProcessNodePath::getX)).collect(Collectors.toList());
    }

    /**
     * @description: 递归获取下一节点
     * @param: processNodePathList 存储可用节点集合
     * @param: flowElements 全部节点
     * @param: sequenceFlow 节点出口连线
     * @param: variables 流程变量
     * @param: gateway 网关
     * @return: void
     * @author: gssong
     * @date: 2022/8/23 19:40
     */
    private static void getNextNodeList(List<ProcessNodePath> processNodePathList, Collection<FlowElement> flowElements, SequenceFlow sequenceFlow, Map<String, Object> variables,String gateway) {
        FlowElement targetFlowElement = sequenceFlow.getTargetFlowElement();
        List<SequenceFlow> outgoingFlows = ((FlowNode) targetFlowElement).getOutgoingFlows();
        for (SequenceFlow outgoingFlow : outgoingFlows) {
            FlowElement currentFlowElement = outgoingFlow.getTargetFlowElement();
            if (currentFlowElement instanceof UserTask) {
                nextNodeBuild(processNodePathList, flowElements, currentFlowElement, outgoingFlow, variables, gateway);
                // 排他网关
            } else if (currentFlowElement instanceof ExclusiveGateway) {
                getNextNodeList(processNodePathList, flowElements, outgoingFlow, variables, FlowConstant.EXCLUSIVE_GATEWAY);
                //并行网关
            } else if (currentFlowElement instanceof ParallelGateway) {
                getNextNodeList(processNodePathList, flowElements, outgoingFlow, variables, FlowConstant.PARALLEL_GATEWAY);
                //包含网关
            } else if (currentFlowElement instanceof InclusiveGateway) {
                getNextNodeList(processNodePathList, flowElements, outgoingFlow, variables, FlowConstant.INCLUSIVE_GATEWAY);
            } else if (currentFlowElement instanceof SubProcess) {
                Collection<FlowElement> subFlowElements = ((SubProcess) currentFlowElement).getFlowElements();
                for (FlowElement element : subFlowElements) {
                    if (element instanceof StartEvent) {
                        List<SequenceFlow> startOutgoingFlows = ((StartEvent) element).getOutgoingFlows();
                        for (SequenceFlow subOutgoingFlow : startOutgoingFlows) {
                            FlowElement subTargetFlowElement = subOutgoingFlow.getTargetFlowElement();
                            if (subTargetFlowElement instanceof UserTask) {
                                nextNodeBuild(processNodePathList, subFlowElements, subTargetFlowElement, subOutgoingFlow, variables, FlowConstant.SUB_PROCESS);
                                break;
                            }
                        }
                    }
                }
                nextNodeBuild(processNodePathList, flowElements, currentFlowElement, outgoingFlow, variables, FlowConstant.SUB_PROCESS);
                getNextNodeList(processNodePathList, flowElements, outgoingFlow, variables, FlowConstant.SUB_PROCESS);
            } else if (currentFlowElement instanceof EndEvent) {
                nextNodeBuild(processNodePathList, flowElements, currentFlowElement, outgoingFlow, variables, FlowConstant.SUB_PROCESS);
            }
        }

    }

    /**
     * @description: 判断网关构建节点
     * @param: processNodePathList 存储可用节点集合
     * @param: flowElements 全部节点
     * @param: currentFlowElement 当前节点
     * @param: sequenceFlow 节点出口连线
     * @param: variableMap 流程变量
     * @param: gateway 网关
     * @return: void
     * @author: gssong
     * @date: 2022/8/23 20:11
     */
    private static void nextNodeBuild(List<ProcessNodePath> processNodePathList, Collection<FlowElement> flowElements, FlowElement currentFlowElement, SequenceFlow sequenceFlow, Map<String, Object> variableMap, String gateway) {
        String conditionExpression = sequenceFlow.getConditionExpression();
        ProcessNodePath processNodePath = new ProcessNodePath();
        FlowElement sourceFlowElement = sequenceFlow.getSourceFlowElement();
        //排他网关
        if (FlowConstant.EXCLUSIVE_GATEWAY.equals(gateway)) {
            buildData(processNodePath, conditionExpression, variableMap, currentFlowElement, sourceFlowElement, FlowConstant.EXCLUSIVE_GATEWAY, processNodePathList);
            //包含网关
        } else if (FlowConstant.INCLUSIVE_GATEWAY.equals(gateway)) {
            buildData(processNodePath, conditionExpression, variableMap, currentFlowElement, sourceFlowElement, FlowConstant.INCLUSIVE_GATEWAY, processNodePathList);
        } else {
            buildData(processNodePath, conditionExpression, variableMap, currentFlowElement, sourceFlowElement, FlowConstant.USER_TASK, processNodePathList);
        }
        getNextNodeList(processNodePathList, flowElements, sequenceFlow, variableMap, null);
    }

    /**
     * @description: 构建数据
     * @param: processNodePath 数据对象
     * @param: conditionExpression 网关条件
     * @param: variableMap 流程变量
     * @param: currentFlowElement 当前节点
     * @param: sourceFlowElement 当前节点的上一节点(用户节点或者网关)
     * @param: gateway 网关
     * @param: processNodePathList 存储可用节点集合
     * @return: void
     * @author: gssong
     * @date: 2022/8/23 20:26
     */
    private static void buildData(ProcessNodePath processNodePath, String conditionExpression, Map<String, Object> variableMap, FlowElement currentFlowElement, FlowElement sourceFlowElement, String gateway, List<ProcessNodePath> processNodePathList) {
        if (FlowConstant.USER_TASK.equals(gateway)) {
            processNodePath.setExpression(true);
            processNodePath.setExpressionStr(true);
        } else {
            //判断是否有条件
            Boolean condition = false;
            processNodePath.setExpressionStr(false);
            if (StringUtils.isNotBlank(conditionExpression)) {
                ExpressCheckCmd expressCheckCmd = new ExpressCheckCmd(conditionExpression, variableMap);
                condition = PROCESS_ENGINE.getManagementService().executeCommand(expressCheckCmd);
                processNodePath.setExpressionStr(true);
            }
            processNodePath.setExpression(condition);
        }
        processNodePath.setNodeId(currentFlowElement.getId());
        processNodePath.setNodeName(currentFlowElement.getName());
        processNodePath.setSourceFlowElementId(sourceFlowElement.getId());
        processNodePath.setNodeType(gateway);
        List<String> collect = processNodePathList.stream().map(ProcessNodePath::getNodeId).collect(Collectors.toList());
        if (!collect.contains(currentFlowElement.getId())) {
            processNodePathList.add(processNodePath);
        }
    }

}

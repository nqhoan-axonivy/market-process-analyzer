package com.axonivy.utils.process.analyzer.internal;

import static java.util.Collections.emptyList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.process.analyzer.helper.ProcessAnalyzerHelper;
import com.axonivy.utils.process.analyzer.internal.model.CommonElement;
import com.axonivy.utils.process.analyzer.internal.model.ProcessElement;
import com.axonivy.utils.process.analyzer.internal.model.TaskParallelGroup;
import com.axonivy.utils.process.analyzer.model.DetectedElement;
import com.axonivy.utils.process.analyzer.model.DetectedTask;

import ch.ivyteam.ivy.process.model.BaseElement;
import ch.ivyteam.ivy.process.model.connector.SequenceFlow;
import ch.ivyteam.ivy.process.model.element.EmbeddedProcessElement;
import ch.ivyteam.ivy.process.model.element.SingleTaskCreator;
import ch.ivyteam.ivy.process.model.element.TaskAndCaseModifier;
import ch.ivyteam.ivy.process.model.element.event.start.RequestStart;
import ch.ivyteam.ivy.process.model.element.gateway.TaskSwitchGateway;
import ch.ivyteam.ivy.process.model.element.value.task.TaskConfig;
import ch.ivyteam.ivy.workflow.ICase;
import ch.ivyteam.ivy.workflow.ITask;
import ch.ivyteam.ivy.workflow.TaskState;

@SuppressWarnings("restriction")
public abstract class ProcessAnalyzer {
	private static final List<TaskState> OPEN_TASK_STATES = List.of(TaskState.SUSPENDED, TaskState.PARKED, TaskState.RESUMED);
	protected ProcessGraph processGraph;
	
	protected ProcessAnalyzer() {
		this.processGraph = new ProcessGraph();
	}

	protected abstract Map<String, Duration> getDurationOverrides();
	protected abstract Map<String, String> getProcessFlowOverrides();

	protected List<ProcessElement> findPath(ProcessElement... from) throws Exception {
		WorkflowPath workflowPath = new WorkflowPath(getProcessFlowOverrides());
		List<ProcessElement> path = workflowPath.findPath(from);		
		return path.stream().distinct().toList();
	}
	
	protected List<ProcessElement> findPath(String flowName, ProcessElement... from) throws Exception {
		WorkflowPath workflowPath = new WorkflowPath(getProcessFlowOverrides());
		List<ProcessElement> path = workflowPath.findPath(flowName, from);
		return path;
	}
	
	protected Duration calculateTotalDuration(List<ProcessElement> path, Enum<?> useCase) {
		WorkflowTime workflowTime = new WorkflowTime(getDurationOverrides());
		return workflowTime.calculateTotalDuration(path, useCase);
	}
	
	protected TaskConfig getStartTaskConfigFromTaskSwitchGateway(SequenceFlow sequenceFlow) {
		return processGraph.getStartTaskConfig(sequenceFlow);
	}
	
	protected List<DetectedElement> convertToDetectedElements(List<ProcessElement> path, Enum<?> useCase) {
		List<DetectedElement> result = convertToDetectedElements(path, useCase, new Date());
		return result;
	}
	
	protected List<ProcessElement> convertToProcessElement(List<ITask> tasks) {
		List<CommonElement> elements = tasks.stream()
				.map(ProcessAnalyzerHelper::getBaseElementOf)
				.map(CommonElement::new)
				.distinct()
				.toList();

		List<ProcessElement> result = new ArrayList<>();
		result.addAll(elements);
		
		return result;
	}
	
	protected List<ITask> getCaseITasks(ICase icase) {
		List<ITask> tasks = icase.tasks().all().stream().filter(task -> OPEN_TASK_STATES.contains(task.getState())).toList();	

		return tasks;
	}
	
	private List<DetectedElement> convertToDetectedElements(List<ProcessElement> path, Enum<?> useCase, Date startedAt) {

		// convert to both detected task and alternative
		List<DetectedElement> result = new ArrayList<>();		
		Date startAtTime = getEndTimestamp(result, startedAt);
		
		for(int i = 0; i < path.size(); i++) {
			
			ProcessElement element = path.get(i);			
		
			// CommonElement(RequestStart)
			if (processGraph.isRequestStart(element.getElement())) {
				continue;
			}
			
			if (processGraph.isTaskAndCaseModifier(element.getElement()) && processGraph.isSystemTask(element.getElement())) {
				continue;
			}
			
			if (element instanceof TaskParallelGroup) {
				var tasks = convertToDetectedElementFromTaskParallelGroup((TaskParallelGroup) element, useCase, startAtTime);
				if (isNotEmpty(tasks)) {
					result.addAll(tasks);
					startAtTime = getMaxEndTimestamp(tasks);
				}
				continue;
			}
			
			// CommonElement(SingleTaskCreator)
			if (processGraph.isSingleTaskCreator(element.getElement())) {
				SingleTaskCreator singleTask = (SingleTaskCreator)element.getElement();
				var detectedTask = createDetectedTask(singleTask, singleTask.getTaskConfig(), startAtTime, useCase);
				if (detectedTask != null) {
					result.add(detectedTask);
					startAtTime = getEndTimestamp(result, startedAt);
				}
				continue;
			}
			
			if (element instanceof CommonElement && processGraph.isSequenceFlow(element.getElement())) {
				SequenceFlow sequenceFlow = (SequenceFlow) element.getElement();
				if (sequenceFlow.getSource() instanceof TaskSwitchGateway) {
					var startTask = createStartTaskFromTaskSwitchGateway(sequenceFlow, startAtTime, useCase);
					if (startTask != null) {
						result.add(startTask);
						startAtTime = getEndTimestamp(result, startedAt);
					}
					continue;
				}
			}
		}
		
		return result.stream().filter(item -> item != null).toList();		
	}
	
	private List<DetectedElement> convertToDetectedElementFromTaskParallelGroup(TaskParallelGroup group, Enum<?> useCase, Date startedAt) {	
		WorkflowTime workflowTime = new WorkflowTime(getDurationOverrides());
		Map<SequenceFlow, List<ProcessElement>> sortedInternalPath =  new LinkedHashMap<>();
		sortedInternalPath.putAll(workflowTime.getInternalPath(group.getInternalPaths(), true));
		sortedInternalPath.putAll(workflowTime.getInternalPath(group.getInternalPaths(), false));
		
		List<DetectedElement> result = new ArrayList<>();
		for (Entry<SequenceFlow, List<ProcessElement>> entry : sortedInternalPath.entrySet()) {
			var startTask = createStartTaskFromTaskSwitchGateway(entry.getKey(), startedAt, useCase);
			var tasks = convertToDetectedElements(entry.getValue(), useCase, ((DetectedTask)startTask).calculateEstimatedEndTimestamp());
			
			result.add(startTask);
			result.addAll(tasks);
		}
		
		return result;
	}
		
	private Date getEndTimestamp(List<DetectedElement> detectedElements, Date defaultAt) {
		List<DetectedTask> detectedTasks = detectedElements.stream()
				.filter(item -> item instanceof DetectedTask)
				.map(DetectedTask.class::cast)
				.toList();
		int size =  detectedTasks.size();
		return size > 0 ? detectedTasks.get(size - 1).calculateEstimatedEndTimestamp() : defaultAt;
	}
	
	private Date getMaxEndTimestamp(List<DetectedElement> detectedElements) {
		Date maxEndTimeStamp = detectedElements.stream()
				.filter(item -> item instanceof DetectedTask == true)
				.map(DetectedTask.class::cast)
				.map(DetectedTask::calculateEstimatedEndTimestamp)				
				.max(Date::compareTo)
				.orElse(null);
				
		return maxEndTimeStamp;
	}
	
	private DetectedElement createStartTaskFromTaskSwitchGateway(SequenceFlow sequenceFlow, Date startedAt, Enum<?> useCase) {

		DetectedElement task = null;
		if (sequenceFlow.getSource() instanceof TaskSwitchGateway) {
			TaskSwitchGateway taskSwitchGateway = (TaskSwitchGateway) sequenceFlow.getSource();
			if (!processGraph.isSystemTask(taskSwitchGateway)) {
				TaskConfig startTask = getStartTaskConfigFromTaskSwitchGateway(sequenceFlow);
				task = createDetectedTask((TaskAndCaseModifier) taskSwitchGateway, startTask, startedAt, useCase);
			}
		}
		return task;
	}
	
	private DetectedElement createDetectedTask(TaskAndCaseModifier task, TaskConfig taskConfig, Date startedAt, Enum<?> useCase) {
		WorkflowTime workflowTime = new WorkflowTime(getDurationOverrides());
		DetectedTask detectedTask = new DetectedTask();
		
		detectedTask.setPid(processGraph.getTaskId(task, taskConfig));		
		detectedTask.setParentElementNames(getParentElementNames(task));
		detectedTask.setTaskName(taskConfig.getName().getRawMacro());
		detectedTask.setElementName(task.getName());
		Duration estimatedDuration = workflowTime.getDuration(task, taskConfig, useCase);				
		detectedTask.setEstimatedDuration(estimatedDuration);
		detectedTask.setEstimatedStartTimestamp(startedAt);		
		String customerInfo = getCustomInfoByCode(taskConfig);
		detectedTask.setCustomInfo(customerInfo);		
		
		return detectedTask;
	}
	
	private List<String> getParentElementNames(TaskAndCaseModifier task){
		List<String> parentElementNames = emptyList();
		if(task.getParent() instanceof EmbeddedProcessElement) {
			parentElementNames = processGraph.getParentElementNamesEmbeddedProcessElement(task.getParent());
		}		
		return parentElementNames ;
	}
	
	private String getCustomInfoByCode(TaskConfig task) {
		String wfEstimateCode = processGraph.getCodeLineByPrefix(task, "APAConfig.setCustomInfo");		
		String result = Optional.ofNullable(wfEstimateCode)
				.filter(StringUtils::isNotEmpty)
				.map(it -> StringUtils.substringBetween(it, "(\"", "\")"))
				.orElse(null);;
		
		return result;
	}
}

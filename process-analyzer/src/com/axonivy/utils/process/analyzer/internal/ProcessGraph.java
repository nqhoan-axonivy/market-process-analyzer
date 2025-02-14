package com.axonivy.utils.process.analyzer.internal;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.defaultString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import com.axonivy.utils.process.analyzer.model.ElementTask;

import ch.ivyteam.ivy.process.model.BaseElement;
import ch.ivyteam.ivy.process.model.Process;
import ch.ivyteam.ivy.process.model.connector.SequenceFlow;
import ch.ivyteam.ivy.process.model.element.EmbeddedProcessElement;
import ch.ivyteam.ivy.process.model.element.SingleTaskCreator;
import ch.ivyteam.ivy.process.model.element.TaskAndCaseModifier;
import ch.ivyteam.ivy.process.model.element.activity.SubProcessCall;
import ch.ivyteam.ivy.process.model.element.event.start.RequestStart;
import ch.ivyteam.ivy.process.model.element.event.start.StartEvent;
import ch.ivyteam.ivy.process.model.element.gateway.Alternative;
import ch.ivyteam.ivy.process.model.element.gateway.TaskSwitchGateway;
import ch.ivyteam.ivy.process.model.element.value.IvyScriptExpression;
import ch.ivyteam.ivy.process.model.element.value.task.TaskConfig;
import ch.ivyteam.ivy.process.model.element.value.task.TaskIdentifier;

public class ProcessGraph {

	private enum Role {
		SYSTEM
	};

	public String getCodeLineByPrefix(TaskConfig task, String... prefix) {
		// strongly typed!
		String script = Optional.of(task.getScript()).orElse(EMPTY);
		return getCodeLineByPrefix(script, prefix);
	}

	public String getCodeLineByPrefix(String script, String... prefix) {
		String[] codeLines = script.split("\\n");
		String wfEstimateCode = Arrays.stream(codeLines).filter(line -> containPrefixs(line, prefix)).findFirst()
				.orElse(EMPTY);
		return wfEstimateCode;
	}

	public List<String> getParentElementNamesEmbeddedProcessElement(BaseElement parentElement) {
		List<String> result = new ArrayList<>();
		if (parentElement instanceof EmbeddedProcessElement) {
			EmbeddedProcessElement processElement = (EmbeddedProcessElement) parentElement;
			List<String> parentElementNames = getParentElementNamesEmbeddedProcessElement(processElement.getParent());

			// Add parent element name at first
			result.addAll(parentElementNames);
			// Add child at last
			result.add(processElement.getName());
		}

		return result;
	}

	public BaseElement findOneStartElementOfProcess(Process process) {
		BaseElement start = process.getElements().stream().filter(item -> item instanceof StartEvent).findFirst()
				.orElse(null);

		return start;
	}

	public List<String> getNextTargetIdsByCondition(Alternative alternative, String condition) {
		IvyScriptExpression script = IvyScriptExpression.script(defaultString(condition));
		List<String> nextTargetIds = alternative.getConditions().conditions().entrySet().stream()
				.filter(entry -> script.equals(entry.getValue())).map(Entry::getKey).toList();

		return nextTargetIds;
	}

	public TaskConfig getStartTaskConfig(SequenceFlow sequenceFlow) {
		BaseElement taskSwitchGateway = sequenceFlow.getSource();
		TaskConfig taskConfig = null;
		if (taskSwitchGateway instanceof TaskSwitchGateway) {
			// ivp=="TaskA.ivp"
			String condition = sequenceFlow.getCondition();
			// "TaskA.ivp"
			String startTask = Arrays.stream(condition.split("==")).skip(1).limit(1).findFirst().orElse(null);

			taskConfig = ((TaskSwitchGateway) taskSwitchGateway).getAllTaskConfigs().stream()
					.filter(it -> startTask.contains(it.getTaskIdentifier().getTaskIvpLinkName())).findFirst()
					.orElse(null);
		}
		return taskConfig;
	}

	public boolean isSystemTask(BaseElement task) {
		if (task instanceof TaskAndCaseModifier) {
			return ((TaskAndCaseModifier) task).getAllTaskConfigs().stream()
					.anyMatch(it -> Role.SYSTEM.name().equals(it.getActivator().getName()));
		}
		return false;
	}

	public ElementTask getElementTask(SingleTaskCreator task) {
		return ElementTask.createSingle(task.getPid().getRawPid());
	}

	public ElementTask createElementTask(TaskAndCaseModifier task, TaskConfig taskConfig) {
		String pid = task.getPid().getRawPid();
		if (task instanceof TaskSwitchGateway) {
			String taskIdentifier = Optional.ofNullable(taskConfig).map(TaskConfig::getTaskIdentifier)
					.map(TaskIdentifier::getRawIdentifier).orElse(EMPTY);

			return ElementTask.createGateway(pid, taskIdentifier);
		} else {
			return ElementTask.createSingle(pid);
		}
	}

	public boolean isRequestStart(BaseElement element) {
		return element instanceof RequestStart;
	}

	public boolean isTaskAndCaseModifier(BaseElement element) {
		return element instanceof TaskAndCaseModifier;
	}

	public boolean isSingleTaskCreator(BaseElement element) {
		return element instanceof SingleTaskCreator;
	}

	public boolean isSequenceFlow(BaseElement element) {
		return element instanceof SequenceFlow;
	}

	public boolean isTaskSwitchGateway(BaseElement element) {
		return element instanceof TaskSwitchGateway;
	}

	public boolean isAlternative(BaseElement element) {
		return element instanceof Alternative;
	}

	public boolean isSubProcessCall(BaseElement element) {
		return element instanceof SubProcessCall;
	}

	public boolean isHandledAsTask(SubProcessCall subProcessCall) {
		return containPrefixs(subProcessCall.getParameters().getCode(), "APAConfig.handleAsTask");
	}

	private boolean containPrefixs(String content, String... prefix) {
		return List.of(prefix).stream().allMatch(it -> content.contains(it));
	}
}
package com.axonivy.utils.process.analyzer.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.assertj.core.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.process.analyzer.AdvancedProcessAnalyzer;
import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.process.model.BaseElement;

@IvyTest
@SuppressWarnings("restriction")
public class FlowExampleLoopTest extends FlowExampleTest {

	private static BaseElement start;
	private static final String PROCESS_NAME = "FlowExampleLoop";
	
	@BeforeAll
	public static void setup() {
		setup(PROCESS_NAME);
		start = ProcessGraphHelper.findByElementName(process, "start");
	}
	
	@BeforeEach
	public void setupForEach() {
		processAnalyzer = new AdvancedProcessAnalyzer(process);	
	}
	
	@Test
	void shouldFindTasksOnPathAtStartWithFlowNameNull() throws Exception {
		var detectedTasks = processAnalyzer.findTasksOnPath(start, null, null);
		
		assertArrayEquals(Arrays.array("Task A", "Task B"), getTaskNames(detectedTasks));
	}
	
	@Test
	void shouldFindAllTasksStartWithFlowNameNull() throws Exception {
		var detectedTasks = processAnalyzer.findAllTasks(start, null);

		assertArrayEquals(Arrays.array("Task A", "Task B"), getTaskNames(detectedTasks));
	}
	
	@Test
	void shouldFindTasksOnPathAtStartWithFlowNameSuccess() throws Exception {
		var detectedTasks = processAnalyzer.findTasksOnPath(start, null, "success");

		assertArrayEquals(Arrays.array("Task A"), getTaskNames(detectedTasks));
	}
	
}

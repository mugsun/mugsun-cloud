package com.mugsun.cloud.flow;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.system.service.HandlerSelectService;
import com.mugsun.boot.system.service.MugsunFlowListener;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.invoker.FrameInvoker;
import org.dromara.warm.flow.core.listener.GlobalListener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.dromara.warm.flow.core.utils.ListenerUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 要走到 AI 预审节点的操作在分派阶段被拦下。监听器按流程引擎取全局监听器的方式装进引擎，
 * 从引擎执行各阶段监听器的入口进去；节点、任务、实例用假的。
 */
class AiApprovalNodeGateTest {

	private static final List<String> HANDLERS = List.of(FlowConstants.PLACEHOLDER_INITIATOR);

	private final HandlerSelectService selectService = mock(HandlerSelectService.class);

	private final AiApprovalNodeGate gate = new AiApprovalNodeGate(new MugsunFlowListener(selectService));

	@BeforeEach
	void installIntoTheEngine() {
		install(gate);
	}

	@AfterEach
	void restoreTheEngine() {
		FrameInvoker.setBeanFunction(null);
		FlowEngine.initGlobalListener(null);
	}

	@Test
	void startingAFlowWhoseFirstNodeIsAiIsRefused() {
		ListenerVariable variable = assignment(plain("start"), "PASS", ai("n1"));

		assertThatThrownBy(() -> ListenerUtil.executeAssignment(variable))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGate.ENTER_MESSAGE);
		verifyNoInteractions(selectService);
	}

	@Test
	void passingIntoAnAiNodeIsRefusedEvenWhenItIsOneOfSeveralNextNodes() {
		ListenerVariable variable = assignment(plain("n1"), "PASS", plain("n2"), ai("n3"));

		assertThatThrownBy(() -> ListenerUtil.executeAssignment(variable))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGate.ENTER_MESSAGE);
		verifyNoInteractions(selectService);
	}

	@Test
	void rejectingOrRevokingBackToAnAiNodeIsRefused() {
		// 退回和撤回在引擎里都是 REJECT：退回的目标、撤回后回到的第一个节点是 AI 节点时，也会给它建任务
		ListenerVariable variable = assignment(plain("n2"), "REJECT", ai("n1"));

		assertThatThrownBy(() -> ListenerUtil.executeAssignment(variable))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGate.ENTER_MESSAGE);
		verifyNoInteractions(selectService);
	}

	@Test
	void operationThatDoesNotReachAnAiNodeGoesThroughToTheBootListener() {
		ListenerVariable variable = assignment(plain("n1"), "PASS", plain("n2"), plain("end"));

		assertThatCode(() -> ListenerUtil.executeAssignment(variable)).doesNotThrowAnyException();
		verify(selectService).resolveHandlers(HANDLERS, "7");
	}

	@Test
	void taskSittingOnAnAiNodeCannotBePassed() {
		ListenerVariable variable = assignment(ai("n1"), "PASS", plain("n2"));

		assertThatThrownBy(() -> ListenerUtil.executeAssignment(variable))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGate.STAY_MESSAGE);
		verifyNoInteractions(selectService);
	}

	@Test
	void taskSittingOnAnAiNodeIsRefusedWhenTheEngineDoesNotSayWhichWayItGoes() {
		ListenerVariable variable = assignment(ai("n1"), null, plain("n2"));

		assertThatThrownBy(() -> ListenerUtil.executeAssignment(variable))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGate.STAY_MESSAGE);
	}

	@Test
	void taskSittingOnAnAiNodeCanBeRejectedBack() {
		ListenerVariable variable = assignment(ai("n2"), "REJECT", plain("n1"));

		assertThatCode(() -> ListenerUtil.executeAssignment(variable)).doesNotThrowAnyException();
		verify(selectService).resolveHandlers(HANDLERS, "7");
	}

	@Test
	void otherStagesAreOnlyForwarded() {
		// 只有分派阶段带着下一批节点；别的阶段即使带了也不在这里拦
		ListenerVariable variable = assignment(plain("n1"), "PASS", ai("n2"));

		assertThatCode(() -> ListenerUtil.executeStart(variable)).doesNotThrowAnyException();
		assertThatCode(() -> ListenerUtil.executeFinish(variable)).doesNotThrowAnyException();
		assertThatCode(() -> ListenerUtil.executeCreate(variable)).doesNotThrowAnyException();
		verifyNoInteractions(selectService);
	}

	@Test
	void aiNodeIsRecognisedByItsListenerPath() {
		assertThat(AiApprovalNodeGate.isAiNode(node("n", FlowConstants.AI_APPROVAL_LISTENER))).isTrue();
		assertThat(AiApprovalNodeGate.isAiNode(node("n", FlowConstants.AI_APPROVAL_LISTENER + "({\"a\":1})"))).isTrue();
		assertThat(AiApprovalNodeGate.isAiNode(node("n", "com.example.Other@@ " + FlowConstants.AI_APPROVAL_LISTENER))).isTrue();
		assertThat(AiApprovalNodeGate.isAiNode(node("n", "com.example.Other@@com.example.Another"))).isFalse();
		assertThat(AiApprovalNodeGate.isAiNode(node("n", " "))).isFalse();
		assertThat(AiApprovalNodeGate.isAiNode(plain("n"))).isFalse();
		assertThat(AiApprovalNodeGate.isAiNode(null)).isFalse();
	}

	@Test
	void startupCheckPassesWhenTheEngineUsesTheGate() {
		assertThatCode(gate::afterSingletonsInstantiated).doesNotThrowAnyException();
	}

	@Test
	void startupFailsWhenTheEngineGotNoGlobalListener() {
		restoreTheEngine();

		assertThatThrownBy(gate::afterSingletonsInstantiated)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("引擎没有取到");
	}

	@Test
	void startupFailsWhenTheEngineUsesTheBootListenerDirectly() {
		install(new MugsunFlowListener(selectService));

		assertThatThrownBy(gate::afterSingletonsInstantiated)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(MugsunFlowListener.class.getName());
	}

	@Test
	void engineTakesTheGateWhenBothListenersAreSpringBeans() {
		restoreTheEngine();
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(HandlerSelectService.class, () -> selectService);
			// 流程引擎初始化时做的事：按类型向 Spring 要一个 GlobalListener，要不到或者出错都当作没有
			context.registerBean("engineInit", Object.class, () -> {
				FrameInvoker.<Object>setBeanFunction(context::getBean);
				FlowEngine.initGlobalListener(null);
				return new Object();
			});
			context.register(MugsunFlowListener.class, AiApprovalNodeGate.class);

			context.refresh();

			assertThat(FlowEngine.globalListener()).isSameAs(context.getBean(AiApprovalNodeGate.class));
			assertThat(context.getBeansOfType(GlobalListener.class)).hasSize(2);
		}
	}

	private static void install(GlobalListener listener) {
		FrameInvoker.<Object>setBeanFunction(type -> GlobalListener.class.equals(type) ? listener : null);
		FlowEngine.initGlobalListener(null);
	}

	private static ListenerVariable assignment(Node current, String skipType, Node... next) {
		Task nextTask = mock(Task.class);
		when(nextTask.getPermissionList()).thenReturn(HANDLERS);
		Instance instance = mock(Instance.class);
		when(instance.getCreateBy()).thenReturn("7");
		return new ListenerVariable()
			.setDefinition(mock(Definition.class))
			.setInstance(instance)
			.setNode(current)
			.setNextNodes(List.of(next))
			.setNextTasks(List.of(nextTask))
			.setFlowParams(skipType == null ? null : FlowParams.build().skipType(skipType));
	}

	private static Node plain(String code) {
		return node(code, null);
	}

	private static Node ai(String code) {
		Node node = node(code, FlowConstants.AI_APPROVAL_LISTENER);
		when(node.getListenerType()).thenReturn("create");
		return node;
	}

	private static Node node(String code, String listenerPath) {
		Node node = mock(Node.class);
		when(node.getNodeCode()).thenReturn(code);
		when(node.getListenerPath()).thenReturn(listenerPath);
		return node;
	}
}

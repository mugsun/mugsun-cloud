package com.mugsun.cloud.system.ai;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.dromara.warm.flow.core.strategy.ListenerStrategy;
import org.dromara.warm.flow.core.utils.ListenerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流程走到 AI 预审节点时报错：从流程引擎建任务时执行监听器的入口进去，节点用假的。
 */
class AiApprovalNodeGuardTest {

	@BeforeEach
	void register() {
		new AiApprovalNodeGuard();
	}

	@Test
	void creatingTheTaskOfAnAiNodeFails() {
		assertThatThrownBy(() -> ListenerUtil.executeCreate(taskCreatedOn(FlowConstants.AI_APPROVAL_LISTENER)))
			.isInstanceOf(ServiceException.class)
			.hasMessage(AiApprovalNodeGuard.MESSAGE);
	}

	@Test
	void aiListenerWithParametersFailsToo() {
		assertThatThrownBy(() -> ListenerUtil.executeCreate(taskCreatedOn(FlowConstants.AI_APPROVAL_LISTENER + "(1)")))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void aiListenerAmongOtherListenersFailsToo() {
		Node node = mock(Node.class);
		when(node.getListenerType()).thenReturn("assignment,create");
		when(node.getListenerPath()).thenReturn("com.example.NoSuchListener@@" + FlowConstants.AI_APPROVAL_LISTENER);

		assertThatThrownBy(() -> ListenerUtil.executeCreate(variable(node)))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void otherStagesOfTheSameNodeAreNotAffected() {
		// AI 节点只登记了 create 监听器，引擎执行别的阶段时不会碰到它
		assertThatCode(() -> ListenerUtil.executeStart(taskCreatedOn(FlowConstants.AI_APPROVAL_LISTENER)))
			.doesNotThrowAnyException();
	}

	@Test
	void engineSilentlySkipsAListenerWhoseClassIsMissing() {
		// 这就是要拦的原因：类找不到时引擎不报错。用一个不存在的类名确认引擎还是这个行为，没有别的策略接手
		assertThatCode(() -> ListenerUtil.executeCreate(taskCreatedOn("com.mugsun.boot.ai.flow.NoSuchListener")))
			.doesNotThrowAnyException();
	}

	@Test
	void registersOnlyOnce() {
		new AiApprovalNodeGuard();

		assertThat(ListenerStrategy.EXPRESSION_STRATEGY_LIST.stream().filter(AiApprovalNodeGuard.class::isInstance))
			.hasSize(1);
	}

	private static ListenerVariable taskCreatedOn(String listenerPath) {
		Node node = mock(Node.class);
		when(node.getListenerType()).thenReturn("create");
		when(node.getListenerPath()).thenReturn(listenerPath);
		return variable(node);
	}

	private static ListenerVariable variable(Node node) {
		return new ListenerVariable().setNode(node).setDefinition(mock(Definition.class));
	}
}

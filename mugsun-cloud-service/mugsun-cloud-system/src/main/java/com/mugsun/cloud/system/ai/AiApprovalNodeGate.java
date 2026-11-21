package com.mugsun.cloud.system.ai;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.system.service.MugsunFlowListener;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.constant.FlowCons;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.listener.GlobalListener;
import org.dromara.warm.flow.core.listener.Listener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批流要走到 AI 预审节点时，在写库之前把这次操作拦下。
 *
 * <p>AI 预审节点靠一个监听器工作：流程定义里存的是它的类名 {@link FlowConstants#AI_APPROVAL_LISTENER}，节点的任务建出来之后，
 * 流程引擎按类名找到它，由它调用 AI 应用，再决定通过、退回还是转人工。这个类在 AI 模块里，微服务版的 system 没有它。
 * 流程引擎找不到监听器的类时不报错，直接跳过：节点就成了一个办理人是发起人的普通任务，发起人自己点一下就过了，
 * 预审等于没有。
 *
 * <p>拦的位置要在写库之前。流程引擎的操作不在事务里，等到任务建出来再报错，实例和这条任务已经在库里了，
 * 发起人照样能在待办里点过去。引擎里会给下一个节点建任务的操作只有发起、办理（通过和退回）、撤回三种，
 * 它们都在算出下一批节点之后、写库之前执行「分派」阶段的监听器，所以拦在这个阶段：
 * <ul>
 * <li>下一批节点里有 AI 预审节点：拒绝，提示 {@link #ENTER_MESSAGE}。条件分支没有走到 AI 节点的不受影响。</li>
 * <li>当前节点就是 AI 预审节点（单体版里留下来的任务）：只能退回，不能通过，提示 {@link #STAY_MESSAGE}。</li>
 * </ul>
 * 作废、转办、加签不建新任务，不执行分派阶段，所以不受影响：停在 AI 节点前后的单子可以作废。
 * 会签、票签节点紧跟着 AI 节点时，引擎在分派之前会先清掉其他人的待办，这一步拦不住；流程仍然停在原来的节点。
 *
 * <p>分派阶段只有全局监听器能看到所有流程，而引擎只认一个全局监听器，已经被 {@link MugsunFlowListener} 占了（它负责解析办理人）。
 * 这个类把它包起来：用 {@link Primary} 让引擎取到的是这个类，先做上面的检查，再把所有通知原样转给它。
 * 引擎取全局监听器失败时不报错，只是当作没有，所以启动完成前要确认引擎拿到的确实是这个类，不是就拒绝启动。
 *
 * <p>要让 AI 预审节点在微服务版里真正能用，得改 mugsun-boot：把监听器里操作流程的部分和调用 AI 的部分拆开，
 * 前一半留在 system，后一半放到 ai 服务。在那之前，带这种节点的流程在微服务版里走不到也走不过这个节点。
 */
@Component
@Primary
public class AiApprovalNodeGate implements GlobalListener, SmartInitializingSingleton {

	static final String ENTER_MESSAGE = "流程的下一步是 AI 预审节点，微服务版暂时不支持这种节点，这次操作没有生效。"
		+ "请让管理员在流程设计里去掉这个节点并重新发布；已经在办的单子请撤回或作废后重新发起";

	static final String STAY_MESSAGE = "这一步是 AI 预审节点，微服务版暂时不支持这种节点，不能通过。"
		+ "请作废这张单子，等管理员在流程设计里去掉这个节点并重新发布后再发起";

	private static final Logger log = LoggerFactory.getLogger(AiApprovalNodeGate.class);

	private final MugsunFlowListener delegate;

	public AiApprovalNodeGate(MugsunFlowListener delegate) {
		this.delegate = delegate;
	}

	@Override
	public void notify(String type, ListenerVariable variable) {
		if (Listener.LISTENER_ASSIGNMENT.equals(type)) {
			refuseAiNode(variable);
		}
		delegate.notify(type, variable);
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (!(FlowEngine.globalListener() instanceof AiApprovalNodeGate)) {
			throw new IllegalStateException("流程引擎的全局监听器不是 AiApprovalNodeGate，实际是 "
				+ describe(FlowEngine.globalListener()) + "。办理人的解析和 AI 预审节点的拦截都靠它，没装上就不能启动。"
				+ "引擎取全局监听器失败时不报错，请检查有没有配置 warm-flow.global-listener-path，或者又加了别的 GlobalListener");
		}
		log.info("流程的全局监听器已装好：要走到 AI 预审节点的操作在写库之前拦下");
	}

	static boolean isAiNode(Node node) {
		String paths = node == null ? null : node.getListenerPath();
		if (paths == null || paths.isBlank()) {
			return false;
		}
		for (String path : paths.split(FlowCons.SPLIT_AT)) {
			if (path.trim().startsWith(FlowConstants.AI_APPROVAL_LISTENER)) {
				return true;
			}
		}
		return false;
	}

	private static void refuseAiNode(ListenerVariable variable) {
		List<Node> nextNodes = variable.getNextNodes();
		if (nextNodes != null && nextNodes.stream().anyMatch(AiApprovalNodeGate::isAiNode)) {
			log.warn("拦下一次要走到 AI 预审节点的操作：{}，要去的节点 {}", where(variable),
				nextNodes.stream().map(Node::getNodeCode).toList());
			throw new ServiceException(ENTER_MESSAGE);
		}
		if (isAiNode(variable.getNode()) && !isReject(variable.getFlowParams())) {
			log.warn("拦下一次在 AI 预审节点上的办理：{}", where(variable));
			throw new ServiceException(STAY_MESSAGE);
		}
	}

	private static boolean isReject(FlowParams params) {
		return params != null && SkipType.isReject(params.getSkipType());
	}

	private static String where(ListenerVariable variable) {
		return "流程定义 " + (variable.getDefinition() == null ? null : variable.getDefinition().getId())
			+ "，实例 " + (variable.getInstance() == null ? null : variable.getInstance().getId())
			+ "，当前节点 " + (variable.getNode() == null ? null : variable.getNode().getNodeCode());
	}

	private static String describe(GlobalListener listener) {
		return listener == null ? "空（引擎没有取到）" : listener.getClass().getName();
	}
}

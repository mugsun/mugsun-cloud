package com.mugsun.cloud.system.ai;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.warm.flow.core.strategy.ListenerStrategy;
import org.dromara.warm.flow.core.utils.ExpressionUtil;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审批流走到 AI 预审节点时直接报错，不让流程往下走。
 *
 * <p>AI 预审节点靠一个监听器工作：流程定义里存的是它的类名 {@link FlowConstants#AI_APPROVAL_LISTENER}，节点的任务建出来时，
 * 流程引擎按类名找到它，由它调用 AI 应用，再决定通过、退回还是转人工。这个类在 AI 模块里，微服务版的 system 没有它。
 * 流程引擎找不到监听器的类时不报错，直接跳过：节点就成了一个办理人是发起人的普通任务，发起人自己点一下就过了，
 * 预审等于没有。
 *
 * <p>流程引擎执行监听器之前，会先问一遍登记过的 {@link ListenerStrategy}：监听器的路径以哪个策略的 {@link #getType()} 开头，
 * 就交给哪个策略。这里用 AI 监听器的类名登记一个策略，轮到它时抛异常：发起或审批的那次操作整个回滚，
 * 操作的人看到的是 {@link #MESSAGE}。
 *
 * <p>要让 AI 预审节点在微服务版里真正能用，得改 mugsun-boot：把监听器里操作流程的部分和调用 AI 的部分拆开，
 * 前一半留在 system，后一半放到 ai 服务。在那之前，带这种节点的流程在微服务版里走不过这个节点。
 */
@Component
public class AiApprovalNodeGuard implements ListenerStrategy {

	static final String MESSAGE = "流程里有 AI 预审节点，微服务版暂时不支持这种节点。请让管理员在流程设计里去掉它，重新发布后再发起";

	public AiApprovalNodeGuard() {
		// 策略登记在流程引擎的静态列表里，同一个进程里只登记一次
		if (EXPRESSION_STRATEGY_LIST.stream().noneMatch(AiApprovalNodeGuard.class::isInstance)) {
			ExpressionUtil.setExpression(this);
		}
	}

	@Override
	public String getType() {
		return FlowConstants.AI_APPROVAL_LISTENER;
	}

	@Override
	public Boolean eval(String expression, Map<String, Object> variable) {
		throw new ServiceException(MESSAGE);
	}
}

package com.mugsun.cloud.system.ai;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.warm.flow.core.strategy.ListenerStrategy;
import org.dromara.warm.flow.core.utils.ExpressionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 预审节点的第二道检查：节点的任务已经建出来、引擎要执行 AI 监听器时报错。
 *
 * <p>正常情况下轮不到它：{@link AiApprovalNodeGate} 在写库之前就把要走到 AI 预审节点的操作拦下了。
 * 它防的是前一道漏掉的情况：流程引擎找不到 {@link FlowConstants#AI_APPROVAL_LISTENER} 这个类时不报错，直接跳过，
 * 节点就成了一个办理人是发起人的普通任务，谁也不会察觉。
 *
 * <p>流程引擎执行监听器之前，会先问一遍登记过的 {@link ListenerStrategy}：监听器的路径以哪个策略的 {@link #getType()} 开头，
 * 就交给哪个策略。这里用 AI 监听器的类名登记一个策略，轮到它时记一条错误日志并抛异常，操作的人看到的是 {@link #MESSAGE}。
 *
 * <p>它报错时任务已经在库里了：流程引擎的操作不在事务里，抛异常不会把已经写进去的实例和任务撤掉。
 * 这条任务不会被人点过去，靠的也是 {@link AiApprovalNodeGate}：停在 AI 预审节点上的任务只能退回或作废，不能通过。
 */
@Component
public class AiApprovalNodeGuard implements ListenerStrategy {

	static final String MESSAGE = "流程里有 AI 预审节点，微服务版暂时不支持这种节点。请让管理员在流程设计里去掉它，重新发布后再发起";

	private static final Logger log = LoggerFactory.getLogger(AiApprovalNodeGuard.class);

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
		log.error("AI 预审节点的任务被建出来了，写库之前的拦截没有生效。这条任务不能通过，请作废对应的单子，并检查流程的全局监听器");
		throw new ServiceException(MESSAGE);
	}
}

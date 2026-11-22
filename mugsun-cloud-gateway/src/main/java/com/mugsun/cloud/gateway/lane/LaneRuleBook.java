package com.mugsun.cloud.gateway.lane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 正在用的那份灰度规则。新来一份配置时整份检查，合格才换上；有一处不合格就整份不用，原来的规则不动。
 * 空的配置表示不要灰度规则：所有请求都没有灰度标记，只发给正式实例。
 *
 * <p>没有启用灰度规则（{@code mugsun.gateway.lanes} 不是 true）时没有人往这里送配置，规则一直是空的。
 * 规则只在这个网关实例的内存里，重启以后从 Nacos 重新读。
 *
 * <p>每收到一份和上一次不一样的配置就记一行日志，都以「灰度规则」开头：换上了、清空了、不合格没有用，
 * 或者算出来的规则和现在的一样、不用换。改完 Nacos 里的配置，看网关日志里有没有新的这一行，就知道改动到没到、收没收。
 */
@Component
public class LaneRuleBook {

	public enum Status {
		/** 这份配置已经换上 */
		UPDATED,
		/** 和正在用的一样，什么都没做 */
		UNCHANGED,
		/** 不合格，没有用，原来的规则不动 */
		REJECTED
	}

	/**
	 * @param status  这份配置的下场
	 * @param lanes   现在生效的规则里有几个灰度标记
	 * @param problem 不合格的原因；合格时为 null
	 */
	public record Outcome(Status status, int lanes, String problem) {
	}

	private static final Logger log = LoggerFactory.getLogger(LaneRuleBook.class);

	private volatile LaneRules current = LaneRules.NONE;

	/**
	 * 上一次送来的全文，不管当时收没收。同一份接连送来两次（启动时读一次，监听又送一次），第二次的结果和第一次没有区别，
	 * 不再记一遍日志；只在 load 里读写
	 */
	private String lastContent;

	/**
	 * @param content 配置的全文；null 或空白表示不要灰度规则
	 */
	public synchronized Outcome load(String content) {
		boolean repeated = Objects.equals(content, lastContent);
		lastContent = content;
		LaneRules rules;
		try {
			rules = LaneRuleParser.parse(content);
		} catch (InvalidLaneRulesException e) {
			if (!repeated) {
				log.warn("灰度规则没有更新，继续用原来的（{}）。原因：{}", current.describe(), e.getMessage());
			}
			return new Outcome(Status.REJECTED, current.size(), e.getMessage());
		}
		if (rules.equals(current)) {
			if (!repeated) {
				// 改了注释、换了写法，或者把写错的改回了原样：规则不用换，但要让改配置的人看到这次改动送到了
				log.info("灰度规则不用换：新收到的配置和现在的一样（{}）", current.describe());
			}
			return new Outcome(Status.UNCHANGED, current.size(), null);
		}
		current = rules;
		if (rules.isEmpty()) {
			log.info("灰度规则已清空：配置被删掉了，或者里面没有规则。现在所有请求都只发给正式实例");
		} else {
			log.info("灰度规则已更新，现在是：{}", rules.describe());
		}
		return new Outcome(Status.UPDATED, rules.size(), null);
	}

	/** 现在有没有规则。没有的话所有请求都没有灰度标记，不用一个个去算 */
	public boolean hasRules() {
		return !current.isEmpty();
	}

	/**
	 * 按正在用的规则，这次请求带哪个灰度标记。先看什么后看什么见 {@link LaneRules}。
	 *
	 * @param clientIp  客户端地址；算不出来时是空串
	 * @param host      客户端访问的域名；算不出来时是空串
	 * @param requested 请求头 X-Mugsun-Lane 的值；没带是 null
	 * @return 标记的名字；不走灰度返回 null
	 */
	public String laneFor(String clientIp, String host, String requested) {
		return current.decide(clientIp, host, requested);
	}

	/** 正在用的规则 */
	LaneRules current() {
		return current;
	}
}

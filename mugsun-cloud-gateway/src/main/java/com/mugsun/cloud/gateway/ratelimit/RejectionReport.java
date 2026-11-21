package com.mugsun.cloud.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 把「有请求被限流拒绝了」记进日志，又不让日志被刷满。
 *
 * <p>限流起作用的时候往往正是请求最多的时候，每拒绝一个就记一条警告会把日志刷满；一条都不记，访问日志又可能没记这些请求
 * （只记有问题的请求时，429 不在里面），用户说「请求过于频繁」时无从查起。所以：安静了一阵之后的第一次拒绝马上记一条，
 * 写明是哪条规则、哪个客户端；之后每隔 {@link #INTERVAL} 记一条这段时间又拒绝了多少个、来自多少个地址、谁最多，
 * 直到有一整段时间没有拒绝。
 */
public final class RejectionReport {

	/** 隔多久记一条汇总 */
	public static final Duration INTERVAL = Duration.ofSeconds(60);

	/** 一段时间里最多分别记这么多个地址各被拒绝了多少次；再多的地址只算进总数 */
	static final int MAX_TRACKED_CLIENTS = 1000;

	private static final Logger log = LoggerFactory.getLogger(RejectionReport.class);

	private static final class Counter {
		private final AtomicLong count = new AtomicLong();
		private volatile String rule;
	}

	private final Scheduler scheduler;
	private final AtomicBoolean reporting = new AtomicBoolean();
	private final AtomicLong rejected = new AtomicLong();
	private final Map<String, Counter> byClient = new ConcurrentHashMap<>();

	/**
	 * @param scheduler 到时间记汇总用的调度器
	 */
	public RejectionReport(Scheduler scheduler) {
		this.scheduler = scheduler;
	}

	/**
	 * 有一个请求被拒绝了。
	 *
	 * @param rule   规则的名字（带限额）
	 * @param client 客户端的标识
	 * @param detail 这个请求的说明（方法、路径、请求编号等），只在要记第一条时才取
	 */
	public void rejected(String rule, String client, Supplier<String> detail) {
		if (reporting.compareAndSet(false, true)) {
			schedule();
			log.warn("开始限流：规则 {}，客户端 {}，{}。接下来每 {} 秒记一条这段时间又拒绝了多少个请求，直到不再有请求被拒绝",
				rule, client, detail.get(), INTERVAL.toSeconds());
			return;
		}
		rejected.incrementAndGet();
		Counter counter = byClient.get(client);
		if (counter == null && byClient.size() < MAX_TRACKED_CLIENTS) {
			counter = byClient.computeIfAbsent(client, key -> new Counter());
		}
		if (counter != null) {
			counter.count.incrementAndGet();
			counter.rule = rule;
		}
	}

	/** 约好过一段时间来记汇总。约不上（调度器已经关了，比如网关正在停）就退回「没在报」，不能让请求因为记日志而出错 */
	private void schedule() {
		try {
			scheduler.schedule(this::report, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			reporting.set(false);
		}
	}

	private void report() {
		long total = rejected.getAndSet(0);
		if (total == 0) {
			byClient.clear();
			reporting.set(false);
			// 清零和改状态之间有空隙：正好这时被拒绝的请求已经记了数，却没有人会来报，这里补约一次
			if (rejected.get() > 0 && reporting.compareAndSet(false, true)) {
				schedule();
			}
			return;
		}
		int clients = 0;
		String topClient = null;
		String topRule = null;
		long topCount = 0;
		for (Iterator<Map.Entry<String, Counter>> it = byClient.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Counter> entry = it.next();
			it.remove();
			long count = entry.getValue().count.get();
			clients++;
			if (count > topCount) {
				topCount = count;
				topClient = entry.getKey();
				topRule = entry.getValue().rule;
			}
		}
		schedule();
		if (topClient == null) {
			log.warn("限流还在继续：过去 {} 秒又拒绝了 {} 个请求", INTERVAL.toSeconds(), total);
			return;
		}
		// 各地址的次数是边记边清的，和总数可能差几个，所以写「约」；总数是准的
		log.warn("限流还在继续：过去 {} 秒又拒绝了 {} 个请求，来自{} {} 个地址；最多的是 {}（约 {} 个，规则 {}）",
			INTERVAL.toSeconds(), total, clients >= MAX_TRACKED_CLIENTS ? "至少" : "", clients, topClient, topCount,
			topRule);
	}
}

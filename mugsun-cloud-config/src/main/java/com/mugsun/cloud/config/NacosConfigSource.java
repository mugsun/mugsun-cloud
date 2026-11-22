package com.mugsun.cloud.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.function.Consumer;

/**
 * 从 Nacos 读这个进程自己要的几份配置（比如网关的动态路由、灰度规则）：开始收请求之前各读一次，之后哪一份改了（包括被删掉）
 * 就重新读哪一份。几份配置共用一条到 Nacos 的连接；一份都不要读时不去连。内容合不合格、用不用，由各自的接收方决定。
 *
 * <p>读不到（Nacos 连不上、账号没有读配置的权限）不影响启动，用到这份配置的功能先按没有它运行；一份读不到不影响别的。
 *
 * <p>改动靠 Nacos 的监听送过来。监听要的权限比读一份配置大：账号要有所在命名空间下配置的读权限。只给这一份配置的读权限时，
 * 启动这一次读得到，以后的改动收不到，Nacos 的客户端也不报错（Nacos 3.0.3 实测）。所以启动时的那行日志把这一点写明。
 *
 * <p>不要加定时重读：Nacos 连不上又没有本地快照时，读到的也是空，和配置被删掉分不出来，定时重读会在 Nacos 出故障时把配置清掉。
 */
public class NacosConfigSource implements SmartLifecycle {

	/** 连 Nacos 的办法。单独列出来，测试时可以换成假的 */
	@FunctionalInterface
	public interface Connector {

		ConfigService connect() throws NacosException;
	}

	/**
	 * 要读的一份配置。
	 *
	 * @param what    这份配置是什么，写日志用，比如「动态路由」。接收方自己打的日志也要以它开头：启动时的那行日志叫人按这个开头去找
	 * @param without 读不到时怎么运行，写日志用，比如「先只用 application.yml 里的路由」
	 * @param dataId  配置的 Data ID
	 * @param group   配置所在的分组
	 * @param sink    内容交给谁。交过去的是配置的全文，这份配置不存在或者被删掉时是 null。在可以等待的线程上调用（启动线程、Nacos 的回调线程）
	 */
	public record Watch(String what, String without, String dataId, String group, Consumer<String> sink) {

		public Watch {
			if (dataId == null || dataId.isBlank()) {
				throw new IllegalArgumentException(what + "的 Data ID 不能为空");
			}
			if (group == null || group.isBlank()) {
				throw new IllegalArgumentException(what + "所在的 Nacos 分组不能为空");
			}
			dataId = dataId.trim();
			group = group.trim();
		}
	}

	/** 比开始收请求（网页服务器启动）早：一开始收请求，这几份配置就已经在了；停的时候反过来，先停收请求 */
	static final int PHASE = 0;

	private static final Logger log = LoggerFactory.getLogger(NacosConfigSource.class);
	private static final long READ_TIMEOUT_MILLIS = 5000;

	/** 一份配置，加上挂在它上面的监听 */
	private final class Subscription {

		private final Watch watch;

		private final Listener listener = new AbstractListener() {
			@Override
			public void receiveConfigInfo(String content) {
				onChange(Subscription.this, content);
			}
		};

		/** 这次启动以来监听有没有送来过内容。读写都拿着外面这个对象的锁 */
		private boolean changedSinceStart;

		private Subscription(Watch watch) {
			this.watch = watch;
		}
	}

	private final Connector connector;
	private final List<Subscription> subscriptions;

	private ConfigService configService;
	private boolean running;

	public NacosConfigSource(Connector connector, List<Watch> watches) {
		this.connector = connector;
		this.subscriptions = watches.stream().map(Subscription::new).toList();
	}

	@Override
	public void start() {
		synchronized (this) {
			if (running) {
				return;
			}
			running = true;
			subscriptions.forEach(subscription -> subscription.changedSinceStart = false);
		}
		if (subscriptions.isEmpty()) {
			return;
		}
		ConfigService service;
		try {
			service = connector.connect();
		} catch (NacosException | RuntimeException e) {
			for (Subscription subscription : subscriptions) {
				Watch watch = subscription.watch;
				log.warn("读不到 Nacos 里的{}（Data ID {}，分组 {}），{}。原因：{}", watch.what(), watch.dataId(),
					watch.group(), watch.without(), brief(e));
			}
			return;
		}
		synchronized (this) {
			configService = service;
		}
		for (Subscription subscription : subscriptions) {
			read(service, subscription);
		}
	}

	private void read(ConfigService service, Subscription subscription) {
		Watch watch = subscription.watch;
		String content;
		try {
			// 先挂监听再读：读的这一下前后发生的改动不会漏掉。这几步不拿锁，监听的回调随时可能进来
			service.addListener(watch.dataId(), watch.group(), subscription.listener);
			content = service.getConfig(watch.dataId(), watch.group(), READ_TIMEOUT_MILLIS);
		} catch (NacosException | RuntimeException e) {
			if (e instanceof NacosException nacos && nacos.getErrCode() == NacosException.NO_RIGHT) {
				// 监听已经挂上，权限补上以后 Nacos 的客户端会自己把内容送过来
				log.warn("接入 Nacos 的账号没有读配置的权限，读不到{}（Data ID {}，分组 {}），{}。"
					+ "给这个账号加上所在命名空间下配置的读权限以后，这里会自己读到，不用重启", watch.what(), watch.dataId(),
					watch.group(), watch.without());
			} else {
				log.warn("读不到 Nacos 里的{}（Data ID {}，分组 {}），{}。原因：{}", watch.what(), watch.dataId(),
					watch.group(), watch.without(), brief(e));
			}
			return;
		}
		synchronized (this) {
			// 监听已经送来过内容，就以那一份为准：它不会比刚才读到的旧
			if (!subscription.changedSinceStart) {
				// 不接异常：接收方在启动这一次抛出来的错要让启动失败。网关第一次刷新路由表时 application.yml 里的路由有错，就要和原来一样启动不了
				watch.sink().accept(content);
			}
		}
		log.info("{}已启用：写在 Nacos 的配置 {}（分组 {}）里，改了马上生效。改完以后这里没有新的一行「{}……」，"
			+ "就是改动没有送到：接入 Nacos 的账号要有所在命名空间下配置的读权限，只能读这一份配置不够", watch.what(),
			watch.dataId(), watch.group(), watch.what());
	}

	private synchronized void onChange(Subscription subscription, String content) {
		if (!running) {
			return;
		}
		subscription.changedSinceStart = true;
		try {
			subscription.watch.sink().accept(content);
		} catch (RuntimeException e) {
			log.error("换上 Nacos 里新的{}时出错", subscription.watch.what(), e);
		}
	}

	@Override
	public void stop() {
		ConfigService service;
		synchronized (this) {
			running = false;
			service = configService;
			configService = null;
		}
		if (service == null) {
			return;
		}
		for (Subscription subscription : subscriptions) {
			try {
				service.removeListener(subscription.watch.dataId(), subscription.watch.group(), subscription.listener);
			} catch (RuntimeException e) {
				log.debug("取消 Nacos 配置的监听时出错", e);
			}
		}
		try {
			service.shutDown();
		} catch (NacosException | RuntimeException e) {
			log.debug("关闭 Nacos 配置客户端时出错", e);
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

	private static String brief(Exception error) {
		String code = error instanceof NacosException nacos ? "错误码 " + nacos.getErrCode() + "，" : "";
		return code + ConfigText.text(error.getMessage());
	}
}

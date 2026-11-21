package com.mugsun.cloud.gateway.route;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 从 Nacos 的一份配置里读动态路由：网关开始收请求之前读一次，之后配置一改（包括被删掉）就重新读。
 * 内容合不合格、用不用，由 {@link DynamicRouteLoader} 决定。
 *
 * <p>读不到（Nacos 连不上、账号没有读配置的权限）不影响网关启动，先只用 application.yml 里的路由。
 *
 * <p>改动靠 Nacos 的监听送过来。监听要的权限比读一份配置大：账号要有所在命名空间下配置的读权限。只给这一份配置的读权限时，
 * 启动这一次读得到，以后的改动收不到，Nacos 的客户端也不报错（Nacos 3.0.3 实测）。所以启动时的那行日志把这一点写明。
 */
public class NacosRouteSource implements SmartLifecycle {

	/** 连 Nacos 的办法。单独列出来，测试时可以换成假的 */
	@FunctionalInterface
	public interface Connector {

		ConfigService connect() throws NacosException;
	}

	/** 比开始收请求（网页服务器启动）早：网关一开始收请求，动态路由就已经在了；停的时候反过来，先停收请求 */
	static final int PHASE = 0;

	private static final Logger log = LoggerFactory.getLogger(NacosRouteSource.class);
	private static final long READ_TIMEOUT_MILLIS = 5000;

	private final String dataId;
	private final String group;
	private final Connector connector;
	private final DynamicRouteLoader loader;

	private final Listener listener = new AbstractListener() {
		@Override
		public void receiveConfigInfo(String content) {
			onChange(content);
		}
	};

	private ConfigService configService;
	private boolean running;
	private boolean changedSinceStart;

	public NacosRouteSource(String dataId, String group, Connector connector, DynamicRouteLoader loader) {
		if (dataId == null || dataId.isBlank()) {
			throw new IllegalArgumentException("mugsun.gateway.dynamic-routes.data-id 不能为空");
		}
		if (group == null || group.isBlank()) {
			throw new IllegalArgumentException("动态路由所在的 Nacos 分组不能为空");
		}
		this.dataId = dataId.trim();
		this.group = group.trim();
		this.connector = connector;
		this.loader = loader;
	}

	@Override
	public void start() {
		ConfigService service;
		synchronized (this) {
			if (running) {
				return;
			}
			running = true;
			changedSinceStart = false;
		}
		String content;
		try {
			service = connector.connect();
			synchronized (this) {
				configService = service;
			}
			// 先挂监听再读：读的这一下前后发生的改动不会漏掉。这几步不拿锁，监听的回调随时可能进来
			service.addListener(dataId, group, listener);
			content = service.getConfig(dataId, group, READ_TIMEOUT_MILLIS);
		} catch (NacosException | RuntimeException e) {
			if (e instanceof NacosException nacos && nacos.getErrCode() == NacosException.NO_RIGHT) {
				// 监听已经挂上，权限补上以后 Nacos 的客户端会自己把内容送过来
				log.warn("接入 Nacos 的账号没有读配置的权限，读不到路由配置（Data ID {}，分组 {}），先只用 application.yml 里的路由。"
					+ "给这个账号加上所在命名空间下配置的读权限以后，网关会自己读到，不用重启", dataId, group);
			} else {
				log.warn("读不到 Nacos 里的路由配置（Data ID {}，分组 {}），先只用 application.yml 里的路由。原因：{}", dataId, group,
					brief(e));
			}
			return;
		}
		synchronized (this) {
			// 监听已经送来过内容，就以那一份为准：它不会比刚才读到的旧
			if (!changedSinceStart) {
				// 不接异常：第一次刷新路由表时 application.yml 里的路由有错，网关要和原来一样启动失败
				loader.load(content);
			}
		}
		log.info("动态路由已启用：路由写在 Nacos 的配置 {}（分组 {}）里，改了马上生效。改完以后这里没有新的一行「动态路由……」，"
			+ "就是改动没有送到：接入 Nacos 的账号要有所在命名空间下配置的读权限，只能读这一份配置不够", dataId, group);
	}

	private synchronized void onChange(String content) {
		if (!running) {
			return;
		}
		changedSinceStart = true;
		try {
			loader.load(content);
		} catch (RuntimeException e) {
			log.error("换上 Nacos 里新的路由配置时出错", e);
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
		try {
			service.removeListener(dataId, group, listener);
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
		return code + InvalidRoutesException.text(error.getMessage());
	}
}

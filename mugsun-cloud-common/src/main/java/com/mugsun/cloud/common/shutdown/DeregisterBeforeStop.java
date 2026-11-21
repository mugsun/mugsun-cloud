package com.mugsun.cloud.common.shutdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 停止时先从注册中心注销，照常处理请求一段时间，然后才开始停。
 *
 * <p>网关和别的服务手里的实例列表不是马上更新的（网关缓存 5 秒）。注销以后马上停，这几秒里它们还会把请求发过来，
 * 这些请求就失败了。所以注销以后先等 {@code mugsun.cloud.shutdown.deregister-wait}：等的时候这个实例和平时一样处理请求，
 * 等到没有人再往这里发了，再开始停。之后才轮到 Spring 自己的停止步骤（不再接新请求、等手上的请求做完）。
 *
 * <p>等待要比网关缓存实例列表的时间（网关的 {@code spring.cloud.loadbalancer.cache.ttl}，5 秒）再长一两秒。
 * 配成 0 就是注销以后马上停。启动到一半失败、或者没有登记到注册中心时，这里什么都不做。
 *
 * <p>排在关闭事件的所有监听器最前面。Spring 的线程池也监听这个事件，收到以后就不再接新任务；
 * 等待排在它们后面的话，等的这几秒里用到异步任务的请求会失败。
 *
 * <p>Spring Cloud Alibaba 自带同样用途的一段（NacosGracefulShutdownDelegate，注销后等
 * {@code spring.cloud.nacos.discovery.graceful-shutdown-wait-time}），这里没有用它，两个原因：
 * <ul>
 *   <li>现在的版本组合下它等不成。Nacos 客户端 3.0.3 在进程退出时自己先做了一部分收尾，框架再去关它时报空指针，
 *       后面的等待被跳过：注销以后约 20 毫秒就开始停了</li>
 *   <li>它先把 Nacos 客户端关掉再等，等的时候这个实例收不到别的服务的实例变化；它在监听器里的顺序也不固定，
 *       可能排在线程池后面</li>
 * </ul>
 * 所以这里只注销、不关客户端，等完以后再停注册。那个空指针在这一步还会出现，这时已经注销过、也等过了，记一行就行。
 * 停过一次以后，框架自带的那一段再停就是空操作，它自己的等待时间在 mugsun-cloud-defaults.yml 里配成了 0，不会再等一遍。
 */
@Component
public class DeregisterBeforeStop implements ApplicationListener<ContextClosedEvent>, Ordered {

	private static final Logger log = LoggerFactory.getLogger(DeregisterBeforeStop.class);

	/** 等一会儿。单测里换成不真等的。 */
	@FunctionalInterface
	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	private final ApplicationContext context;

	private final ObjectProvider<AbstractAutoServiceRegistration<?>> autoRegistration;

	private final ObjectProvider<ServiceRegistry<Registration>> serviceRegistry;

	private final ObjectProvider<Registration> registration;

	private final Duration wait;

	private final Sleeper sleeper;

	@Autowired
	public DeregisterBeforeStop(ApplicationContext context,
								ObjectProvider<AbstractAutoServiceRegistration<?>> autoRegistration,
								ObjectProvider<ServiceRegistry<Registration>> serviceRegistry,
								ObjectProvider<Registration> registration,
								@Value("${mugsun.cloud.shutdown.deregister-wait:0s}") Duration wait) {
		this(context, autoRegistration, serviceRegistry, registration, wait,
			duration -> Thread.sleep(duration.toMillis()));
	}

	DeregisterBeforeStop(ApplicationContext context, ObjectProvider<AbstractAutoServiceRegistration<?>> autoRegistration,
						 ObjectProvider<ServiceRegistry<Registration>> serviceRegistry,
						 ObjectProvider<Registration> registration, Duration wait, Sleeper sleeper) {
		this.context = context;
		this.autoRegistration = autoRegistration;
		this.serviceRegistry = serviceRegistry;
		this.registration = registration;
		// 配置项写成空串时转出来是 null，按 0 处理
		this.wait = wait == null ? Duration.ZERO : wait;
		this.sleeper = sleeper;
	}

	@Override
	public void onApplicationEvent(ContextClosedEvent event) {
		// 只认自己所在的上下文：别的上下文（比如给每个被调用的服务单独建的那些）关闭时不动
		if (event.getApplicationContext() != context) {
			return;
		}
		AbstractAutoServiceRegistration<?> auto = autoRegistration.getIfUnique();
		ServiceRegistry<Registration> registry = serviceRegistry.getIfUnique();
		Registration self = registration.getIfUnique();
		if (auto == null || registry == null || self == null || !auto.isRunning()) {
			return;
		}
		if (deregister(registry, self) && wait.toMillis() > 0) {
			log.info("已请求注册中心注销本实例，继续处理请求 {} 毫秒后开始停止", wait.toMillis());
			try {
				sleeper.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		try {
			auto.stop();
		} catch (RuntimeException e) {
			log.info("关闭注册中心客户端时报错，注销和等待都已经做过，不影响停止：{}", e.toString());
		}
	}

	/**
	 * 注销时抛了异常就不等：别人手里的列表不会变，等了也是白等。
	 *
	 * <p>Nacos 的实现连不上服务端时不抛异常，只记一条错误日志（ERR_NACOS_DEREGISTER），这里分辨不出来，照样等。
	 * 多等的这几秒没有坏处：等的时候照常处理请求。
	 */
	private boolean deregister(ServiceRegistry<Registration> registry, Registration self) {
		try {
			registry.deregister(self);
			return true;
		} catch (RuntimeException e) {
			log.warn("从注册中心注销失败，不等待，直接停止：{}", e.toString());
			return false;
		}
	}

	/** 必须在发布关闭事件的线程里同步执行，等完才轮到后面的监听器和停止步骤 */
	@Override
	public boolean supportsAsyncExecution() {
		return false;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
}

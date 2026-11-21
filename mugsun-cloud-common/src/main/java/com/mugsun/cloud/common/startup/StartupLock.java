package com.mugsun.cloud.common.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 同一个服务的几个实例同时启动时，让「启动时改表结构、写初始数据」这一段一个一个来。
 *
 * <p>mugsun-boot 启动时有一批检查是「先查有没有，没有就改表或插入」（各个 SchemaRunner、DataInitializer）。单体版只有
 * 一个进程，没有问题。同一个服务起了几个实例、又正好一起启动时，几个实例都查到「没有」，都去改、都去插：后到的撞上
 * 唯一索引或「列已存在」，启动失败；没有唯一索引的表（角色、菜单）里留下重复的数据。主库的 Flyway 迁移自己有锁，不在此列。
 *
 * <p>做法：在 Redis 里按服务名占一把锁，占到了才往下启动，启动完成后放开；没占到就等。占锁在内嵌的 Web 服务器开始
 * 接请求之前，所以等的时候这个实例还没有对外提供服务，也还没有登记到注册中心。
 *
 * <p>启动到一半失败时（比如注册中心还没起来，登记不上）把锁放开再退出，不然被重新拉起来的实例要等自己上一次留下的锁过期。
 * 放锁要赶在 Redis 连接关掉之前，见 {@link #setBeanFactory}。
 *
 * <p>锁带过期时间（{@code mugsun.cloud.startup-lock.ttl}，默认 60 秒），持有期间定时续期：持有它的实例被强行杀掉时，
 * 别的实例最多多等一个过期时间。等够 {@code mugsun.cloud.startup-lock.max-wait}（默认 2 分钟）还没轮到，或者 Redis
 * 访问失败时，记一条警告、不加锁继续启动：这时和没有这把锁时一样，不会因为它启动不了。最多等的时间要比容器健康检查
 * 能容忍的启动时间短（镜像里约 6 分钟）。开关 {@code mugsun.cloud.startup-lock.enabled}，默认开。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.startup-lock.enabled", havingValue = "true", matchIfMissing = true)
public class StartupLock implements SmartLifecycle, DisposableBean, BeanNameAware, BeanFactoryAware {

	private static final Logger log = LoggerFactory.getLogger(StartupLock.class);

	static final String KEY_PREFIX = "mugsun:cloud:startup-lock:";

	/**
	 * 生命周期里排在最前面的一批。内嵌 Web 服务器在很靠后的阶段才开始接请求（Integer.MAX_VALUE - 2048），
	 * 先占到锁，再对外提供服务。
	 */
	static final int PHASE = Integer.MIN_VALUE + 1024;

	static final Duration RETRY_INTERVAL = Duration.ofMillis(500);

	private static final RedisScript<Long> RELEASE = RedisScript.of(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

	private static final RedisScript<Long> RENEW = RedisScript.of(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
		Long.class);

	/** 等一会儿再试。单测里换成不真等的。 */
	@FunctionalInterface
	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	private final StringRedisTemplate redis;

	private final String service;

	private final String key;

	private final Duration ttl;

	private final Duration maxWait;

	private final Sleeper sleeper;

	/** 只有值和自己这个对得上才续期、才删除：锁过期后被别的实例占去时，不会把别人的删掉 */
	private final String token = UUID.randomUUID().toString();

	private volatile boolean running;

	private volatile boolean held;

	private ScheduledExecutorService renewer;

	private String beanName;

	@Autowired
	public StartupLock(StringRedisTemplate redis, @Value("${spring.application.name:application}") String service,
					   @Value("${mugsun.cloud.startup-lock.ttl:60s}") Duration ttl,
					   @Value("${mugsun.cloud.startup-lock.max-wait:2m}") Duration maxWait) {
		this(redis, service, ttl, maxWait, duration -> Thread.sleep(duration.toMillis()));
	}

	StartupLock(StringRedisTemplate redis, String service, Duration ttl, Duration maxWait, Sleeper sleeper) {
		this.redis = redis;
		this.service = service;
		this.key = KEY_PREFIX + service;
		this.ttl = ttl;
		this.maxWait = maxWait;
		this.sleeper = sleeper;
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
	}

	/**
	 * 让 Spring 先停这个类，再关 Redis 连接。
	 *
	 * <p>应用关闭、启动到一半失败时，Spring 按阶段从大到小依次停。Redis 连接工厂的阶段是 0，这个类要最先启动、
	 * 阶段很小，轮到它放锁时连接已经关了，锁放不掉，要等它自己过期。Spring 停一个 bean 之前会先停依赖它的 bean，
	 * 所以这里把自己登记成每个连接工厂的依赖方：放锁就排到了关连接前面。
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (beanName == null || !(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
			return;
		}
		for (String connectionFactory : factory.getBeanNamesForType(RedisConnectionFactory.class, false, false)) {
			factory.registerDependentBean(connectionFactory, beanName);
		}
	}

	@Override
	public void start() {
		acquire();
		running = true;
	}

	/** 启动完成：所有监听 ApplicationReadyEvent 的地方（包括启动时加载租户数据源的那个）都执行完以后放开 */
	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.LOWEST_PRECEDENCE)
	public void onReady() {
		release();
	}

	/** 启动到一半失败、应用关闭时也放开，别的实例和重新拉起来的自己都不用等到锁过期 */
	@Override
	public void stop() {
		release();
		running = false;
	}

	@Override
	public void destroy() {
		release();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

	synchronized void acquire() {
		Duration waited = Duration.ZERO;
		while (true) {
			Boolean acquired;
			try {
				acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
			} catch (RuntimeException e) {
				log.warn("访问 Redis 失败，占不到启动锁，不加锁继续启动：{}", e.getMessage());
				return;
			}
			if (Boolean.TRUE.equals(acquired)) {
				held = true;
				startRenewing();
				if (!waited.isZero()) {
					log.info("等了 {} 秒，轮到本实例做启动初始化", waited.toSeconds());
				}
				return;
			}
			if (waited.compareTo(maxWait) >= 0) {
				log.warn("等了 {} 秒，{} 的另一个实例还没做完启动初始化。不再等，不加锁继续启动", waited.toSeconds(), service);
				return;
			}
			if (waited.isZero()) {
				log.info("{} 的另一个实例正在做启动初始化，等它做完再继续（最多等 {} 秒）", service, maxWait.toSeconds());
			}
			try {
				sleeper.sleep(RETRY_INTERVAL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("等启动锁时被打断，不加锁继续启动");
				return;
			}
			waited = waited.plus(RETRY_INTERVAL);
		}
	}

	synchronized void release() {
		boolean wasHeld = held;
		held = false;
		if (renewer != null) {
			renewer.shutdownNow();
			renewer = null;
		}
		if (!wasHeld) {
			return;
		}
		try {
			redis.execute(RELEASE, List.of(key), token);
		} catch (RuntimeException e) {
			log.warn("放开启动锁失败，它会在 {} 秒后自己过期：{}", ttl.toSeconds(), e.getMessage());
		}
	}

	private void startRenewing() {
		renewer = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "startup-lock-renew");
			thread.setDaemon(true);
			return thread;
		});
		long period = Math.max(1000L, ttl.toMillis() / 3);
		renewer.scheduleWithFixedDelay(this::renew, period, period, TimeUnit.MILLISECONDS);
	}

	void renew() {
		if (!held) {
			return;
		}
		try {
			Long renewed = redis.execute(RENEW, List.of(key), token, String.valueOf(ttl.toMillis()));
			// 放开锁和续期正好撞在一起时，续期会扣空或被打断，那不是问题，不报
			if (held && (renewed == null || renewed == 0L)) {
				log.warn("给启动锁续期时发现它已经不在自己手里（过期或被清掉了），别的实例可能已经开始做启动初始化");
			}
		} catch (RuntimeException e) {
			if (held) {
				log.warn("给启动锁续期失败：{}", e.getMessage());
			}
		}
	}
}

package com.mugsun.cloud.common.machine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * 机器号：每个实例启动时领一个别的实例没在用的号，生成主键时带上它，几个实例同时生成的主键就不会相同。
 *
 * <p>绝大多数表的主键由程序生成，由时间、机器号和序号拼成。机器号原来每个进程都是同一个默认值：两个进程在同一毫秒
 * 往同一张表各写一条时，主键可能相同，后写的那一条失败。四个服务共用一个库，所以不只是同一个服务的几个实例之间，
 * 不同服务写同一张表（比如接口日志）时也一样。
 *
 * <p>做法：号码登记在 Redis 里，一个号一个键，值是领它的实例。启动时从 {@value #FIRST} 到 {@value #LAST} 里找一个
 * 没有人登记的占上。所有服务的所有实例共用这一个范围，所以整套最多 98 个实例同时在线。{@value #UNASSIGNED}
 * 不发给任何实例，它是没领到时用的号。
 *
 * <p>登记带过期时间（{@code mugsun.cloud.machine-number.ttl}，默认 3 分钟），持有期间定时续期，停止时删掉。实例被强杀时
 * 来不及删，这个号要等过期以后才会再发出去。找号的起点每次往后挪一位，刚放掉的号要轮过一整圈才会再发给别人：
 * 正在停止的实例、卡住很久才恢复的实例还在用旧号的那一小段时间里，不会和新启动的实例用到同一个号。
 *
 * <p>Redis 访问失败、或者号都被占着时，记一条警告，用 {@value #UNASSIGNED} 继续启动，以后每到续期的时间点再试着领：
 * 这时和没有这个类时一样，不会因为它启动不了。续期时发现号已经登记在别的实例名下（自己的登记过期以后被领走了），
 * 就重新领一个。号换了会通知用到它的地方（{@link #onChange}）。
 *
 * <p>领号在这个类初始化时就做，早于 MyBatis 可用（见 {@link MachineNumberIdGenerators}），启动过程中写库用的也是领到的号。
 * 开关 {@code mugsun.cloud.machine-number.enabled}，默认开。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.machine-number.enabled", havingValue = "true", matchIfMissing = true)
public class MachineNumber implements InitializingBean, SmartLifecycle, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(MachineNumber.class);

	/** 没领到时用的号。不发给任何实例；MyBatis-Flex 自己的默认值也是它 */
	public static final int UNASSIGNED = 1;

	/** 发放范围。上限是 99：MyBatis-Flex 的主键里留给机器号的是两位十进制数 */
	static final int FIRST = 2;

	static final int LAST = 99;

	private static final int POOL_SIZE = LAST - FIRST + 1;

	/** 比 Redis 连接工厂（阶段 0）晚启动、早停止：停止时轮到这里删登记，连接还没关 */
	static final int PHASE = 1;

	private static final Duration MIN_TTL = Duration.ofSeconds(10);

	private final MachineNumberStore store;

	/** 登记在 Redis 里的值。带一段随机串：只有值和自己这个对得上才续期、才删除 */
	private final String owner;

	private final Duration ttl;

	private final long renewEveryMillis;

	private final List<IntConsumer> listeners = new ArrayList<>();

	private volatile int number = UNASSIGNED;

	private volatile boolean running;

	private ScheduledExecutorService renewer;

	@Autowired
	public MachineNumber(MachineNumberStore store, @Value("${spring.application.name:application}") String service,
						 @Value("${mugsun.cloud.machine-number.ttl:3m}") Duration ttl) {
		this(store, ttl, ownerOf(service));
	}

	/** 单测用：直接给出登记在 Redis 里的值 */
	MachineNumber(MachineNumberStore store, Duration ttl, String owner) {
		if (ttl == null || ttl.compareTo(MIN_TTL) < 0) {
			throw new IllegalArgumentException("mugsun.cloud.machine-number.ttl 不能小于 " + MIN_TTL.toSeconds() + " 秒");
		}
		this.store = store;
		this.owner = owner;
		this.ttl = ttl;
		this.renewEveryMillis = ttl.toMillis() / 6;
	}

	/** 服务名@主机名#随机串。主机名只为了在 Redis 里看得出是谁领的，取不到就不写（容器里是容器号或 Pod 名） */
	private static String ownerOf(String service) {
		String host = System.getenv("HOSTNAME");
		return service + (host == null || host.isBlank() ? "" : "@" + host.trim()) + "#" + UUID.randomUUID();
	}

	/** 现在用的机器号。没领到时是 {@value #UNASSIGNED} */
	public int current() {
		return number;
	}

	/** 机器号换了的时候通知。登记时马上按现在的号通知一次 */
	public synchronized void onChange(IntConsumer listener) {
		listeners.add(listener);
		listener.accept(number);
	}

	@Override
	public void afterPropertiesSet() {
		begin();
	}

	/** 号在初始化时已经领了，正常启动走到这里什么都不用做 */
	@Override
	public void start() {
		begin();
	}

	/** 应用关闭、启动到一半失败时把登记删掉，这个号不用等到过期就能再发出去 */
	@Override
	public void stop() {
		release();
	}

	/** 启动失败得早、还没走到生命周期那一步时，Spring 只销毁、不调用 stop，所以这里也删一次 */
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

	private synchronized void begin() {
		if (running) {
			return;
		}
		running = true;
		switchTo(claim());
		renewer = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "machine-number-renew");
			thread.setDaemon(true);
			return thread;
		});
		renewer.scheduleWithFixedDelay(this::renew, renewEveryMillis, renewEveryMillis, TimeUnit.MILLISECONDS);
	}

	/** 找一个没有人登记的号占上。领不到时返回 {@value #UNASSIGNED} */
	private int claim() {
		try {
			long start = store.nextStart();
			for (int i = 0; i < POOL_SIZE; i++) {
				int candidate = FIRST + (int) Math.floorMod(start + i, (long) POOL_SIZE);
				if (store.claim(candidate, owner, ttl)) {
					return candidate;
				}
			}
			log.warn("机器号 {} 到 {} 都被别的实例占着，本实例先用 {}：和别的没领到号的实例同时写同一张表时主键可能相同。{} 秒后再试着领",
				FIRST, LAST, UNASSIGNED, renewEveryMillis / 1000);
		} catch (RuntimeException e) {
			log.warn("访问 Redis 失败，领不到机器号，本实例先用 {}：和别的没领到号的实例同时写同一张表时主键可能相同。{} 秒后再试着领：{}",
				UNASSIGNED, renewEveryMillis / 1000, e.getMessage());
		}
		return UNASSIGNED;
	}

	/** 定时执行：有号就续期，没领到就再试着领 */
	synchronized void renew() {
		if (!running) {
			return;
		}
		int held = number;
		if (held == UNASSIGNED) {
			switchTo(claim());
			return;
		}
		MachineNumberStore.Renewal renewal;
		try {
			renewal = store.renew(held, owner, ttl);
		} catch (RuntimeException e) {
			log.warn("给机器号 {} 续期失败，{} 秒后再试：{}", held, renewEveryMillis / 1000, e.getMessage());
			return;
		}
		switch (renewal) {
			case RENEWED -> {
			}
			case RECLAIMED -> log.warn("机器号 {} 的登记过期过（Redis 断开过、数据被清过，或者本实例卡住过），已经重新登记上", held);
			case TAKEN -> {
				log.warn("机器号 {} 已经登记在别的实例名下（本实例的登记过期以后被领走了），重新领一个", held);
				switchTo(claim());
			}
		}
	}

	private void switchTo(int next) {
		int previous = number;
		if (next == previous) {
			return;
		}
		number = next;
		if (next != UNASSIGNED) {
			log.info("本实例的机器号：{}（生成主键用。登记在 Redis 里，{} 秒过期，每 {} 秒续期一次）", next, ttl.toSeconds(),
				renewEveryMillis / 1000);
		}
		for (IntConsumer listener : listeners) {
			try {
				listener.accept(next);
			} catch (RuntimeException e) {
				log.warn("把机器号 {} 交给主键生成器时出错：{}", next, e.toString());
			}
		}
	}

	private synchronized void release() {
		if (!running) {
			return;
		}
		running = false;
		if (renewer != null) {
			renewer.shutdownNow();
			renewer = null;
		}
		// 号不清掉：停止过程剩下的时间里接着用它生成主键。别的实例要轮过一整圈才会领到它
		int held = number;
		if (held == UNASSIGNED) {
			return;
		}
		try {
			store.release(held, owner);
			log.info("机器号 {} 已归还", held);
		} catch (RuntimeException e) {
			log.warn("删机器号 {} 的登记失败，它会在 {} 秒后自己过期：{}", held, ttl.toSeconds(), e.getMessage());
		}
	}
}

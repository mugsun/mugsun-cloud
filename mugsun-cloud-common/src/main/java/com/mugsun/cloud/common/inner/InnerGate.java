package com.mugsun.cloud.common.inner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 一个内部接口的名额：同时最多放 {@code limit} 个请求进去处理，其中同一个租户最多 {@code perTenant} 个。
 *
 * <p>为什么分租户：名额所有租户共用的话，一个租户发来一批慢请求（慢，但没有慢到超时，调用方的熔断管不到它）
 * 就能把名额占满，别的租户在这个接口上只能得到「忙」。每个租户最多占 {@code perTenant} 个，剩下的它占不到，
 * 别的租户来了马上能用。留出来的那些是别的租户合着用的，不是每家一份。
 *
 * <p>名额满了，或者自己租户的份额满了，新来的先排队，排到时间还轮不到就算了。有名额空出来时从排队的里面挑：
 * 份额没满的租户才能进；这样的不止一个时，先给这时占得最少的租户，占得一样多的先到先得。所以几个租户一起抢时，
 * 各家占的名额趋向一样多；只有一个租户在用时就是先到先得。
 *
 * <p>只记这一刻占着名额的租户和排着队的请求，所以来过多少个不同的租户号都不会越记越多。
 */
final class InnerGate {

	/** 「忙」的日志最少隔这么久打一行 */
	static final long LOG_EVERY_NANOS = 10_000_000_000L;

	private static final Logger log = LoggerFactory.getLogger(InnerGate.class);

	private final String path;

	private final int limit;

	private final int perTenant;

	/** 只用来给日志限频；排队按真实时间算 */
	private final LongSupplier nanoClock;

	private final ReentrantLock lock = new ReentrantLock();

	/** 各租户这时占着几个名额。只有占着名额的租户在里面，所以最多 limit 项 */
	private final Map<String, Integer> held = new HashMap<>();

	/** 这时一共占着几个名额 */
	private int inService;

	/** 排着队的请求，按到的先后。排着的都是这一刻进不去的：名额满了，或者它那个租户的份额满了 */
	private final Set<Waiter> queue = new LinkedHashSet<>();

	private final Throttle fullLog;

	private final Throttle ownShareLog;

	InnerGate(String path, int limit, int perTenant) {
		this(path, limit, perTenant, System::nanoTime);
	}

	InnerGate(String path, int limit, int perTenant, LongSupplier nanoClock) {
		if (limit < 1 || perTenant < 1 || perTenant > limit) {
			throw new IllegalArgumentException("名额至少 1 个，每个租户的份额在 1 和名额总数之间：" + limit + " / " + perTenant);
		}
		this.path = path;
		this.limit = limit;
		this.perTenant = perTenant;
		this.nanoClock = nanoClock;
		long longAgo = nanoClock.getAsLong() - LOG_EVERY_NANOS;
		this.fullLog = new Throttle(longAgo);
		this.ownShareLog = new Throttle(longAgo);
	}

	/**
	 * 替这个租户占一个名额，最多等这么多毫秒（0 是不等）。占到了返回 true，用完必须 {@link #leave}；没占到返回 false。
	 * 等的时候线程被打断就不再等，打断标记留着。
	 */
	boolean enter(String tenant, long waitMillis) {
		Objects.requireNonNull(tenant, "tenant");
		long began = System.nanoTime();
		boolean ownShareFull;
		lock.lock();
		try {
			// 能进就直接进，不算插队：排着的都是这一刻进不去的
			if (inService < limit && heldBy(tenant) < perTenant) {
				take(tenant);
				return true;
			}
			Waiter me = new Waiter(tenant, lock.newCondition());
			queue.add(me);
			try {
				long nanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
				while (!me.admitted && nanos > 0) {
					nanos = me.turn.awaitNanos(nanos);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				// 不管是怎么出来的（轮到了、到时间、被打断，或者等的时候出了别的错），都不留在队里：留着的话，
				// 以后空出来的名额会发给一个已经走掉的请求，再也收不回来。轮到的那一方已经把它拿出队了，再拿一次不碍事
				queue.remove(me);
			}
			if (me.admitted) {
				// 归还名额的一方已经把名额记到它名下了。刚好到时间、刚好被打断的同时轮到它，也算占到：由它用完归还
				return true;
			}
			ownShareFull = perTenant < limit && heldBy(tenant) >= perTenant;
		} finally {
			lock.unlock();
		}
		refused(tenant, ownShareFull, (System.nanoTime() - began) / 1_000_000);
		return false;
	}

	/** 归还 {@link #enter} 占到的名额，空出来的给排队的 */
	void leave(String tenant) {
		lock.lock();
		try {
			Integer count = held.get(tenant);
			if (count == null) {
				throw new IllegalStateException("租户 " + tenant + " 在 " + path + " 上没有占着名额，却来归还");
			}
			if (count == 1) {
				held.remove(tenant);
			} else {
				held.put(tenant, count - 1);
			}
			inService--;
			// 放进一个就够：归还之前排着的都进不去，现在只多出一个名额（也只给这个租户多出一份份额）
			Waiter next = nextInLine();
			if (next != null) {
				queue.remove(next);
				take(next.tenant);
				next.admitted = true;
				next.turn.signal();
			}
		} finally {
			lock.unlock();
		}
	}

	/** 这时一共占着几个名额 */
	int inService() {
		lock.lock();
		try {
			return inService;
		} finally {
			lock.unlock();
		}
	}

	/** 这时有几个请求在排队 */
	int queued() {
		lock.lock();
		try {
			return queue.size();
		} finally {
			lock.unlock();
		}
	}

	/** 这时各租户占着几个名额，按租户号排序 */
	Map<String, Integer> holders() {
		lock.lock();
		try {
			return new TreeMap<>(held);
		} finally {
			lock.unlock();
		}
	}

	private int heldBy(String tenant) {
		return held.getOrDefault(tenant, 0);
	}

	private void take(String tenant) {
		held.merge(tenant, 1, Integer::sum);
		inService++;
	}

	/** 排队的里面该轮到谁：份额没满的租户里这时占得最少的，一样多就是先来的。没有能进的返回 null */
	private Waiter nextInLine() {
		Waiter best = null;
		int fewest = perTenant;
		for (Waiter waiter : queue) {
			int count = heldBy(waiter.tenant);
			if (count < fewest) {
				best = waiter;
				fewest = count;
			}
		}
		return best;
	}

	/** 回了一次「忙」。忙的时候不能每个请求刷一行：两种原因各自 10 秒最多一行，行里写上它代表几次 */
	private void refused(String tenant, boolean ownShareFull, long waitedMillis) {
		int count = (ownShareFull ? ownShareLog : fullLog).due(nanoClock.getAsLong());
		if (count == 0) {
			return;
		}
		if (ownShareFull) {
			log.warn("内部接口 {} 上租户 {} 同时在处理的请求到了每个租户的上限 {} 个（mugsun.cloud.inner.max-concurrent-per-tenant），"
				+ "它新来的请求等了 {}ms 还没轮到，回了「忙」；别的租户不受影响。这样的日志每个接口 10 秒最多一行（不是每个租户一行），"
				+ "这一行代表 {} 次",
				path, tenant, perTenant, waitedMillis, count);
			return;
		}
		String holders = holders().entrySet().stream()
			.map(entry -> "租户 " + entry.getKey() + " " + entry.getValue() + " 个")
			.collect(Collectors.joining("、"));
		log.warn("内部接口 {} 同时在处理的请求到了上限 {} 个（mugsun.cloud.inner.max-concurrent），租户 {} 新来的请求等了 {}ms 还没轮到，"
			+ "回了「忙」。这时占着名额的：{}。这样的日志 10 秒最多一行，这一行代表 {} 次",
			path, limit, tenant, waitedMillis, holders.isEmpty() ? "已经没有了" : holders, count);
	}

	/** 一个排着队的请求 */
	private static final class Waiter {

		private final String tenant;

		private final Condition turn;

		/** 轮到它了：名额已经记在它名下。只在拿着锁的时候读写 */
		private boolean admitted;

		private Waiter(String tenant, Condition turn) {
			this.tenant = tenant;
			this.turn = turn;
		}
	}

	/** 一种日志 10 秒最多放一行，并数着这一行代表几次 */
	private static final class Throttle {

		private final AtomicInteger sinceLogged = new AtomicInteger();

		private final AtomicLong loggedAt;

		private Throttle(long longAgo) {
			this.loggedAt = new AtomicLong(longAgo);
		}

		/** 记一次。该打日志了就返回这一行代表几次，不该打返回 0 */
		private int due(long now) {
			int count = sinceLogged.incrementAndGet();
			long last = loggedAt.get();
			if (now - last >= LOG_EVERY_NANOS && loggedAt.compareAndSet(last, now)) {
				sinceLogged.addAndGet(-count);
				return count;
			}
			return 0;
		}
	}
}

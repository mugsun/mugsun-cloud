package com.mugsun.cloud.common.inner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 调用方这边的熔断：替同一个租户调同一个实例的同一个接口接连失败够多次，就先不调它、直接报失败，过一会儿放一个请求去试。
 *
 * <p>为什么要有：对方连得上却迟迟不回话的时候（查询卡在锁上、进程被挂起），每次调用都要等到超时才知道失败。
 * 不熔断的话，每个用户请求都白等这么久，对方那边没人等的请求也越积越多。
 *
 * <p>按「实例 + 接口 + 租户」分开记：
 * <ul>
 *   <li>不按服务记：一个服务有两个实例、只坏了一个时，好的那个照常用；同一个实例上一个接口卡住
 *       （比如它查的那张表被锁了），别的接口照常调。</li>
 *   <li>租户也分开：有的故障只跟一个租户有关，比如它的独立数据库连不上、它配的大模型不回话、它的数据多到查不完。
 *       合在一起记的话，这一个租户接连失败几次，别的租户本来调得通的请求也被拦下；之后去试探的要是又轮到它，
 *       别的租户就一直用不了。分开记的代价是：对方整个卡住时，每个租户各自要失败够次数才熔断。</li>
 * </ul>
 *
 * <p>只给正在出故障的留记录：调用成功时什么都不记，失败了才开始记，对方答复一次记录就丢掉。一条记录有三种状态：
 * <ul>
 *   <li>正常：照常发请求，记接连失败了几次。</li>
 *   <li>熔断中：接连失败到了 {@code mugsun.cloud.inner.breaker-failures} 次以后进入，不发请求。</li>
 *   <li>试探：熔断满 {@code mugsun.cloud.inner.breaker-pause} 以后放一个请求过去，它回来之前其它请求仍然不发。
 *       它得到答复就恢复正常，否则再停一轮。</li>
 * </ul>
 *
 * <p>什么算失败：连不上、等不到响应、对方返回 5xx、响应没通过验签。对方明确答复了的不算（成功、业务失败、401 和别的 4xx）：
 * 它在正常工作，熔断帮不上忙，反而会把真正的原因（比如密钥不一致）盖住。对方回「忙」既不算失败也不清零：
 * 那是它自己在限制同时处理的个数，过一下就好；但试探的那个请求得到「忙」，说明它还没缓过来，按没恢复处理。
 */
final class InnerBreakers {

	private static final Logger log = LoggerFactory.getLogger(InnerBreakers.class);

	/** 最多留多少条记录。只有正在出故障的才有记录，平时是空的；到了这个数就清一次 */
	static final int MAX_TRACKED = 4096;

	/** 这么久没再调过的记录，清的时候丢掉（实例换了地址、那个租户不再来了） */
	static final long IDLE_MILLIS = 10 * 60 * 1000L;

	private enum Verdict {

		/** 连不上、等不到响应、对方出错、响应不可信 */
		FAILED,

		/** 对方给了明确的答复 */
		ANSWERED,

		/** 对方回的是「忙」 */
		BUSY
	}

	/** 记录按什么分开：实例的地址（主机:端口）、接口、租户 */
	private record Key(String authority, String path, String tenant) {
	}

	private final int threshold;

	private final long pauseMillis;

	/** 只增不减的毫秒数。不用系统时间：系统时间被往回拨的话，熔断会迟迟不结束 */
	private final LongSupplier ticker;

	private final ConcurrentHashMap<Key, Breaker> breakers = new ConcurrentHashMap<>();

	/** 熔断关着时发的凭单：所有调用共用这一张，交回时什么也不记 */
	private final Ticket untracked = new Ticket(null, null, null);

	/**
	 * @param threshold   接连失败几次就熔断；0 是不熔断
	 * @param pauseMillis 熔断以后停多少毫秒再放一个请求去试
	 * @param ticker      只增不减的毫秒数
	 */
	InnerBreakers(int threshold, long pauseMillis, LongSupplier ticker) {
		this.threshold = threshold;
		this.pauseMillis = pauseMillis;
		this.ticker = ticker;
	}

	/**
	 * 要替这个租户调这个实例的这个接口了，先来领一张凭单。
	 *
	 * @param serviceId 对方的服务名，只用在日志里
	 * @param authority 实例的地址（主机:端口）
	 * @param tenant    这次调用带过去的租户
	 * @return 熔断中返回 null，这次不要发请求；否则返回凭单，调用结束时必须 {@link Ticket#close 交回}
	 */
	Ticket enter(String serviceId, String authority, String path, String tenant) {
		if (threshold <= 0) {
			return untracked;
		}
		Key key = new Key(authority, path, tenant);
		Breaker breaker = breakers.get(key);
		if (breaker == null) {
			return new Ticket(key, serviceId, null);
		}
		return breaker.enter(ticker.getAsLong());
	}

	/** 现在留着多少条记录 */
	int tracked() {
		return breakers.size();
	}

	/** 这个「实例 + 接口 + 租户」的记录，没有就新建一条 */
	private Breaker record(Key key, String serviceId, long now) {
		Breaker existing = breakers.get(key);
		if (existing != null) {
			return existing;
		}
		if (breakers.size() >= MAX_TRACKED) {
			prune(now);
		}
		return breakers.computeIfAbsent(key, ignored -> new Breaker(key, serviceId, now));
	}

	private void prune(long now) {
		breakers.values().removeIf(breaker -> breaker.dropIfIdle(now));
		if (breakers.size() >= MAX_TRACKED) {
			// 十分钟里有这么多个「实例 + 接口 + 租户」出过故障，故障面很大。全部清掉重新记：
			// 代价是正在熔断的会被多调几次，换来这张表不会一直长大
			log.warn("熔断的记录到了上限 {} 条，全部清掉重新记：正在熔断的会被再调几次", MAX_TRACKED);
			breakers.values().removeIf(Breaker::drop);
		}
	}

	/** 日志里的时长：按秒写，不是整秒的留一位小数；不到 1 秒的写毫秒 */
	private static String span(long millis) {
		if (millis % 1000 == 0) {
			return millis / 1000 + " 秒";
		}
		return millis < 1000 ? millis + " 毫秒" : String.format(Locale.ROOT, "%.1f 秒", millis / 1000.0);
	}

	/**
	 * 一次发送的凭单：发之前领，结束时交回，交回时按上面标记的结果记账。
	 *
	 * <p>没标记过就交回的按失败记。这样发送的代码不管从哪条路出去（包括抛出没想到的异常），账都记得上，
	 * 试探的那个请求也不会因为忘了报结果而一直占着试探的名额。
	 */
	final class Ticket implements AutoCloseable {

		/** 熔断关着时共用的那一张是 null */
		private final Key key;

		private final String serviceId;

		/** 这张凭单是去试探的：试探的是哪条记录。普通的凭单是 null，交回时再找当时的记录 */
		private final Breaker probed;

		private Verdict verdict = Verdict.FAILED;

		private boolean closed;

		private Ticket(Key key, String serviceId, Breaker probed) {
			this.key = key;
			this.serviceId = serviceId;
			this.probed = probed;
		}

		/** 对方给了明确的答复（成功、业务失败、或者明确拒绝）：它在正常工作 */
		void answered() {
			mark(Verdict.ANSWERED);
		}

		/** 对方回的是「忙」，这次没有执行 */
		void busy() {
			mark(Verdict.BUSY);
		}

		private void mark(Verdict value) {
			// 熔断关着时所有调用共用一张凭单，不往上写东西
			if (key != null) {
				verdict = value;
			}
		}

		@Override
		public void close() {
			if (closed || key == null) {
				return;
			}
			closed = true;
			long now = ticker.getAsLong();
			if (probed != null && probed.leave(true, verdict, now)) {
				return;
			}
			// 普通的请求；或者试探的那条记录已经不在了（别的请求先得到了答复），那就按普通的请求记。
			// 失败了要记上，没有记录就新建；答复和「忙」只在已经有记录时才用得着
			while (true) {
				Breaker current = verdict == Verdict.FAILED ? record(key, serviceId, now) : breakers.get(key);
				if (current == null || current.leave(false, verdict, now)) {
					return;
				}
				// 刚拿到的记录恰好被丢掉了：不等丢它的线程，自己把它从表里拿掉，再找一次
				breakers.remove(key, current);
			}
		}
	}

	/** 一个「实例 + 接口 + 租户」的记录。字段都在持有它自己的锁时读写 */
	private final class Breaker {

		private final Key key;

		private final String serviceId;

		/** 接连失败了几次 */
		private int failures;

		private boolean open;

		/** 这一轮从什么时候开始停 */
		private long pausedAt;

		/** 最早是什么时候熔断的，恢复时用来算前后停了多久 */
		private long trippedAt;

		/** 试探的那个请求发出去了，还没回来 */
		private boolean probing;

		/** 最近一次有调用来领凭单或者交回凭单的时间 */
		private long touched;

		/** 已经从表里拿掉了，不能再往上记 */
		private boolean dropped;

		private Breaker(Key key, String serviceId, long now) {
			this.key = key;
			this.serviceId = serviceId;
			this.touched = now;
		}

		/** 熔断中返回 null；轮到试探返回试探的凭单；其余返回普通的凭单 */
		private synchronized Ticket enter(long now) {
			if (dropped) {
				return new Ticket(key, serviceId, null);
			}
			touched = now;
			if (!open) {
				return new Ticket(key, serviceId, null);
			}
			if (probing || now - pausedAt < pauseMillis) {
				return null;
			}
			probing = true;
			return new Ticket(key, serviceId, this);
		}

		/**
		 * 一张凭单交回来了，按结果记账。
		 *
		 * @param probe 是不是这条记录放出去试探的那个请求
		 * @return false 表示这条记录已经丢掉了，没有记上
		 */
		private boolean leave(boolean probe, Verdict verdict, long now) {
			// 日志放到锁外面打，锁里只改状态
			Runnable report = null;
			synchronized (this) {
				if (dropped) {
					return false;
				}
				touched = now;
				if (verdict == Verdict.ANSWERED) {
					if (open) {
						// 时间是在锁外面读的，两个线程前后脚进来时可能差一点，不让它算出负数
						long paused = Math.max(0, now - trippedAt);
						report = () -> log.info("调用 {}恢复了，前后停了 {}", describe(), span(paused));
					}
					// 对方答复了，这里不再是出故障的：记录丢掉，以后再失败从头记
					dropped = true;
					breakers.remove(key, this);
				} else if (open) {
					// 熔断中只看试探的那个请求；熔断之前发出去、现在才失败的请求不重复记
					if (probe) {
						probing = false;
						pausedAt = now;
						String why = verdict == Verdict.BUSY ? "对方还在忙" : "还是不通";
						report = () -> log.warn("调用 {}试了一次，{}，再停 {}", describe(), why, span(pauseMillis));
					}
				} else if (verdict == Verdict.FAILED && ++failures >= threshold) {
					open = true;
					probing = false;
					pausedAt = now;
					trippedAt = now;
					int count = failures;
					report = () -> log.warn("调用 {}接连失败 {} 次，接下来 {}先不调它，直接报失败；之后放一个请求去试，通了自动恢复",
						describe(), count, span(pauseMillis));
				}
			}
			if (report != null) {
				report.run();
			}
			return true;
		}

		/** 很久没再调过就丢掉。返回丢没丢 */
		private synchronized boolean dropIfIdle(long now) {
			if (now - touched <= IDLE_MILLIS) {
				return false;
			}
			dropped = true;
			return true;
		}

		private synchronized boolean drop() {
			dropped = true;
			return true;
		}

		/** 日志里怎么称呼它。以全角括号结尾，后面直接接话，不加空格 */
		private String describe() {
			return serviceId + " 的 " + key.path() + "（实例 " + key.authority() + "，租户 " + key.tenant() + "）";
		}
	}
}

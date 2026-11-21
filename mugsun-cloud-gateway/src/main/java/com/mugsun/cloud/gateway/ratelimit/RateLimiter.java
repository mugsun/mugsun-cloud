package com.mugsun.cloud.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

import java.util.function.LongSupplier;

/**
 * 数请求：每条规则下的每个客户端一个令牌桶，桶放在这个网关实例的内存里。
 *
 * <p>不放 Redis 是有意的：这是挡在服务前面的第一道闸，拒绝一个请求不该再产生一次网络读写，否则请求一多，
 * 压力就转到了 Redis 上；网关也因此不用拿 Redis 的口令。代价是各个网关实例各数各的：起了 N 个网关时，
 * 一个客户端实际能过的请求最多是限额的 N 倍；网关重启后从头数。要按用户、按租户精确卡数的限额放在服务里做。
 *
 * <p>内存有上限：一个桶歇够了它的时长就丢掉（这时它已经补满，和新建一个没有区别）；桶的总数超过 {@link #MAX_BUCKETS} 时，
 * 最少被用到的先丢。被丢掉的客户端下次来从满桶算起，所以同时有比这还多的地址在发请求时，发得少的那些地址限不准，
 * 发得多的仍然限得住。
 */
public final class RateLimiter {

	/** 最多同时记这么多个桶。实测（JDK 21）一个桶连同它在缓存里的开销约 550 字节，记满时约占 55MB */
	public static final int MAX_BUCKETS = 100_000;

	private record Key(int rule, String client) {
	}

	/**
	 * @param bucket    令牌桶
	 * @param idleNanos 歇够这么久就可以丢掉：等于限额的时长，桶从空到满正好这么久
	 */
	private record Entry(Bucket bucket, long idleNanos) {
	}

	private final TimeMeter timeMeter;
	private final Cache<Key, Entry> buckets;

	/**
	 * @param nanoClock 单调递增的纳秒时钟，正式运行时是 {@code System::nanoTime}
	 */
	public RateLimiter(LongSupplier nanoClock) {
		this(nanoClock, MAX_BUCKETS);
	}

	RateLimiter(LongSupplier nanoClock, int maxBuckets) {
		this.timeMeter = new TimeMeter() {
			@Override
			public long currentTimeNanos() {
				return nanoClock.getAsLong();
			}

			@Override
			public boolean isWallClockBased() {
				return false;
			}
		};
		this.buckets = Caffeine.newBuilder()
			.maximumSize(maxBuckets)
			.ticker(nanoClock::getAsLong)
			.expireAfter(new Expiry<Key, Entry>() {
				@Override
				public long expireAfterCreate(Key key, Entry entry, long currentTime) {
					return entry.idleNanos();
				}

				@Override
				public long expireAfterUpdate(Key key, Entry entry, long currentTime, long currentDuration) {
					return entry.idleNanos();
				}

				@Override
				public long expireAfterRead(Key key, Entry entry, long currentTime, long currentDuration) {
					return entry.idleNanos();
				}
			})
			.build();
	}

	/**
	 * 给这个客户端在这条规则下记一个请求。超了限额的请求不记数，不会让它等得更久。
	 *
	 * @param rule   规则，必须是有限额的（{@link RateLimitRules.Rule#unlimited()} 为假）
	 * @param client 客户端的标识，同一个标识用同一个桶
	 * @return 0 表示没超限额；大于 0 表示超了，值是还要等多少纳秒才轮得到下一个请求
	 */
	public long tryAcquire(RateLimitRules.Rule rule, String client) {
		Entry entry = buckets.get(new Key(rule.index(), client), key -> newEntry(rule.quota()));
		ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
		return probe.isConsumed() ? 0 : Math.max(1, probe.getNanosToWaitForRefill());
	}

	private Entry newEntry(Quota quota) {
		Bandwidth bandwidth = Bandwidth.builder()
			.capacity(quota.requests())
			.refillGreedy(quota.requests(), quota.per())
			.build();
		Bucket bucket = Bucket.builder().addLimit(bandwidth).withCustomTimePrecision(timeMeter).build();
		return new Entry(bucket, quota.per().toNanos());
	}

	/** 现在记着多少个桶（先把该丢的丢掉再数） */
	long bucketCount() {
		buckets.cleanUp();
		return buckets.estimatedSize();
	}
}

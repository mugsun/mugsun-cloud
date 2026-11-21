package com.mugsun.cloud.common.machine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 机器号登记在 Redis 里：一个号一个键，值是领它的实例，带过期时间。
 *
 * <p>「是自己的才续期、才删」和「过期了还没人领就重新登记」都用脚本一次做完，中间不会被别的实例插进来。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.machine-number.enabled", havingValue = "true", matchIfMissing = true)
class RedisMachineNumberStore implements MachineNumberStore {

	static final String KEY_PREFIX = "mugsun:cloud:machine-number:";

	static final String CURSOR_KEY = "mugsun:cloud:machine-number-cursor";

	/** 返回 1：还在自己名下，续上了；2：键已经没了，重新登记上了；0：在别的实例名下 */
	static final RedisScript<Long> RENEW = RedisScript.of("""
		local owner = redis.call('get', KEYS[1])
		if owner == ARGV[1] then
			redis.call('pexpire', KEYS[1], ARGV[2])
			return 1
		end
		if not owner then
			redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
			return 2
		end
		return 0
		""", Long.class);

	static final RedisScript<Long> RELEASE = RedisScript.of(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

	private final StringRedisTemplate redis;

	RedisMachineNumberStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	static String key(int number) {
		return KEY_PREFIX + number;
	}

	@Override
	public long nextStart() {
		// INCR 返回加完以后的值，第一次是 1
		Long value = redis.opsForValue().increment(CURSOR_KEY);
		return value == null ? 0L : value - 1;
	}

	@Override
	public boolean claim(int number, String owner, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(number), owner, ttl));
	}

	@Override
	public Renewal renew(int number, String owner, Duration ttl) {
		Long result = redis.execute(RENEW, List.of(key(number)), owner, String.valueOf(ttl.toMillis()));
		if (result == null) {
			throw new IllegalStateException("续期脚本没有返回结果");
		}
		return switch (result.intValue()) {
			case 1 -> Renewal.RENEWED;
			case 2 -> Renewal.RECLAIMED;
			case 0 -> Renewal.TAKEN;
			default -> throw new IllegalStateException("续期脚本返回了没见过的结果：" + result);
		};
	}

	@Override
	public void release(int number, String owner) {
		redis.execute(RELEASE, List.of(key(number)), owner);
	}
}

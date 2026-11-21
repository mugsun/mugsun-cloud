package com.mugsun.cloud.common.inner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 记住最近用过的随机串，让同一个请求不能被重放第二次。
 *
 * <p>存在 Redis 里，同一个服务的几个实例共用：在一个实例上用过的请求，换一个实例重放也不行。键里带着服务名，
 * 各服务之间互不影响。Redis 用不了时 {@link #firstUse} 会抛异常，调用它的地方按拒绝处理。
 */
@Component
public class InnerNonceStore {

	static final String KEY_PREFIX = "mugsun:inner:nonce:";

	private final StringRedisTemplate redis;

	private final String self;

	public InnerNonceStore(StringRedisTemplate redis, @Value("${spring.application.name}") String self) {
		this.redis = redis;
		this.self = self;
	}

	/**
	 * 记下一个随机串。
	 *
	 * @param ttl 记多久。要盖住这个请求的时间戳还能通过校验的整段时间
	 * @return 第一次见到返回 true，已经见过返回 false
	 */
	public boolean firstUse(String nonce, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + self + ":" + nonce, "1", ttl));
	}
}

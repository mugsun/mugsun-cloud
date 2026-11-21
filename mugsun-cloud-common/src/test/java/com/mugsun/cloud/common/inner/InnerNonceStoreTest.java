package com.mugsun.cloud.common.inner;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InnerNonceStoreTest {

	private static final Duration TTL = Duration.ofSeconds(125);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private InnerNonceStore store(String self) {
		when(redis.opsForValue()).thenReturn(values);
		return new InnerNonceStore(redis, self);
	}

	/** 用「不存在才写入」一步完成判断和记录，两个实例同时收到同一个请求时只有一个能成功 */
	@Test
	void firstUseIsTrueOnlyWhenRedisStoredTheKey() {
		when(values.setIfAbsent("mugsun:inner:nonce:mugsun-gis:abc", "1", TTL)).thenReturn(true, false);
		InnerNonceStore store = store("mugsun-gis");

		assertThat(store.firstUse("abc", TTL)).isTrue();
		assertThat(store.firstUse("abc", TTL)).isFalse();
	}

	/** 键里带服务名：各服务共用一个 Redis，互不影响 */
	@Test
	void keyIsScopedToTheService() {
		when(values.setIfAbsent("mugsun:inner:nonce:mugsun-track:abc", "1", TTL)).thenReturn(true);

		assertThat(store("mugsun-track").firstUse("abc", TTL)).isTrue();
		assertThat(store("mugsun-gis").firstUse("abc", TTL)).as("没有存进去就不算第一次").isFalse();
	}

	/** Redis 没有给出明确结果时不能当成「第一次见」 */
	@Test
	void nullAnswerIsNotFirstUse() {
		when(values.setIfAbsent("mugsun:inner:nonce:mugsun-gis:abc", "1", TTL)).thenReturn(null);

		assertThat(store("mugsun-gis").firstUse("abc", TTL)).isFalse();
	}

	@Test
	void redisFailurePropagatesSoTheCallerCanRefuse() {
		when(values.setIfAbsent("mugsun:inner:nonce:mugsun-gis:abc", "1", TTL))
			.thenThrow(new RedisConnectionFailureException("down"));

		assertThatThrownBy(() -> store("mugsun-gis").firstUse("abc", TTL))
			.isInstanceOf(RedisConnectionFailureException.class);
	}
}

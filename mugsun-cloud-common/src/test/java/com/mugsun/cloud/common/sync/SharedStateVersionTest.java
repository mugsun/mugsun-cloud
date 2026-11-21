package com.mugsun.cloud.common.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedStateVersionTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final SharedStateVersion version = new SharedStateVersion(redis);

	@BeforeEach
	void wire() {
		when(redis.opsForValue()).thenReturn(values);
	}

	@Test
	void readsEmptyWhenNothingHasEverChanged() {
		assertThat(version.current()).isEmpty();
	}

	@Test
	void readsTheValueInRedis() {
		when(values.get(SharedStateVersion.KEY)).thenReturn("改过一次");

		assertThat(version.current()).isEqualTo("改过一次");
	}

	@Test
	void everyBumpWritesAValueNeverSeenBefore() {
		version.bump();
		version.bump();
		version.bump();

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(values, times(3)).set(eq(SharedStateVersion.KEY), written.capture());
		assertThat(written.getAllValues()).doesNotHaveDuplicates().noneMatch(String::isBlank);
	}

	@Test
	void bumpDoesNotFailTheCallerWhenRedisIsUnavailable() {
		doThrow(new RedisConnectionFailureException("连不上 Redis")).when(values).set(anyString(), anyString());

		assertThatCode(version::bump).doesNotThrowAnyException();
	}

	@Test
	void aFailedReadIsLeftToTheCaller() {
		when(values.get(SharedStateVersion.KEY)).thenThrow(new RedisConnectionFailureException("连不上 Redis"));

		assertThatThrownBy(version::current).isInstanceOf(RedisConnectionFailureException.class);
	}
}

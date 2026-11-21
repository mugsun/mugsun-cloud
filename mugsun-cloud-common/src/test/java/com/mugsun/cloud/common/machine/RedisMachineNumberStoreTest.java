package com.mugsun.cloud.common.machine;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机器号在 Redis 里的登记：用的是哪个键、传了什么值、脚本的返回值怎么解释。Redis 用模拟对象代替；
 * 脚本本身在真的 Redis 上按两个实例实测过（docs/deployment.md 第 7 节）。
 */
@SuppressWarnings("unchecked")
class RedisMachineNumberStoreTest {

	private static final Duration TTL = Duration.ofMinutes(3);

	private static final String KEY = "mugsun:cloud:machine-number:7";

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private RedisMachineNumberStore store() {
		when(redis.opsForValue()).thenReturn(values);
		return new RedisMachineNumberStore(redis);
	}

	@Test
	void theSearchStartsAtZeroAndMovesOnByOne() {
		when(values.increment("mugsun:cloud:machine-number-cursor")).thenReturn(1L, 2L);
		RedisMachineNumberStore store = store();

		assertThat(store.nextStart()).isZero();
		assertThat(store.nextStart()).isEqualTo(1);
	}

	@Test
	void claimsOnlyWhenNobodyHoldsTheNumber() {
		when(values.setIfAbsent(KEY, "a", TTL)).thenReturn(true);
		when(values.setIfAbsent(KEY, "b", TTL)).thenReturn(false);
		RedisMachineNumberStore store = store();

		assertThat(store.claim(7, "a", TTL)).isTrue();
		assertThat(store.claim(7, "b", TTL)).isFalse();
	}

	@Test
	void renewalTellsWhetherTheNumberIsStillOurs() {
		when(redis.execute(RedisMachineNumberStore.RENEW, List.of(KEY), "a", "180000")).thenReturn(1L, 2L, 0L, 9L, null);
		RedisMachineNumberStore store = store();

		assertThat(store.renew(7, "a", TTL)).isEqualTo(MachineNumberStore.Renewal.RENEWED);
		assertThat(store.renew(7, "a", TTL)).isEqualTo(MachineNumberStore.Renewal.RECLAIMED);
		assertThat(store.renew(7, "a", TTL)).isEqualTo(MachineNumberStore.Renewal.TAKEN);
		// 看不懂的结果当成这次续期失败，不当成「被别人领走了」：后者会让实例换号
		assertThatThrownBy(() -> store.renew(7, "a", TTL)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.renew(7, "a", TTL)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void releasePassesTheOwnerSoThatOnlyItsOwnRegistrationIsRemoved() {
		store().release(7, "a");

		verify(redis).execute(RedisMachineNumberStore.RELEASE, List.of(KEY), "a");
	}
}

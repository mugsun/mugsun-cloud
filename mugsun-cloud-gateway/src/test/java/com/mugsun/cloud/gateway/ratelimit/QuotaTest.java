package com.mugsun.cloud.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限额的写法：次数/时长。写错了要报错，不能当成没有限额。
 */
class QuotaTest {

	private static final String PROPERTY = "mugsun.gateway.rate-limit";

	@Test
	void readsCountAndDuration() {
		assertThat(Quota.parse("600/60s", PROPERTY)).isEqualTo(new Quota(600, Duration.ofSeconds(60)));
		assertThat(Quota.parse("30/1m", PROPERTY)).isEqualTo(new Quota(30, Duration.ofMinutes(1)));
		assertThat(Quota.parse("5000/1h", PROPERTY)).isEqualTo(new Quota(5000, Duration.ofHours(1)));
		assertThat(Quota.parse("1/1s", PROPERTY)).isEqualTo(new Quota(1, Duration.ofSeconds(1)));
		assertThat(Quota.parse(" 20/10s ", PROPERTY)).isEqualTo(new Quota(20, Duration.ofSeconds(10)));
	}

	@Test
	void acceptsTheLargestCountAndDuration() {
		assertThat(Quota.parse("1000000/3600s", PROPERTY)).isEqualTo(new Quota(1_000_000, Duration.ofHours(1)));
		assertThat(Quota.parse("1/60m", PROPERTY).per()).isEqualTo(Duration.ofHours(1));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "600", "600/", "/60s", "600/60", "600/s", "600/60d", "600/60S", "600 / 60s",
		"600/60s/1", "-1/60s", "1.5/60s", "abc", "unlimited", "600/60ms",
		// 次数和时长超出范围
		"0/60s", "1000001/60s", "10/0s", "10/3601s", "10/61m", "10/2h", "9999999999/60s", "10/9999999s"})
	void anythingElseFailsFast(String text) {
		assertThatThrownBy(() -> Quota.parse(text, PROPERTY))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(PROPERTY)
			.hasMessageContaining("600/60s");
	}

	@Test
	void nullFailsFastToo() {
		assertThatThrownBy(() -> Quota.parse(null, PROPERTY)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void isWrittenInSecondsInLogs() {
		assertThat(Quota.parse("30/1m", PROPERTY)).hasToString("30/60s");
		assertThat(Quota.parse("5000/1h", PROPERTY)).hasToString("5000/3600s");
	}
}

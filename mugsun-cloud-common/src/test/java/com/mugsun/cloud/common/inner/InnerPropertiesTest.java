package com.mugsun.cloud.common.inner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InnerPropertiesTest {

	/** 测试用的假密钥 */
	private static final String SECRET = "test-only-key-0123456789abcdef-XYZ";

	@Test
	void noSecretMeansDisabledAndStillStarts() {
		InnerProperties properties = new InnerProperties();

		assertThatCode(properties::validate).doesNotThrowAnyException();
		assertThat(properties.configured()).isFalse();
	}

	@Test
	void blankOrNullSecretMeansDisabled() {
		for (String blank : new String[] {null, "", "   ", "\n"}) {
			InnerProperties properties = new InnerProperties();
			properties.setSecret(blank);
			properties.validate();

			assertThat(properties.configured()).isFalse();
		}
	}

	@Test
	void shortSecretRefusesToStartWithoutPrintingIt() {
		InnerProperties properties = new InnerProperties();
		properties.setSecret("short-secret-value");

		assertThatThrownBy(properties::validate)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("至少要 32 个字符")
			.hasMessageContaining("MUGSUN_INNER_SECRET")
			.message().doesNotContain("short-secret-value");
	}

	@Test
	void secretOfMinimumLengthIsAccepted() {
		InnerProperties properties = new InnerProperties();
		properties.setSecret("x".repeat(InnerProperties.MIN_SECRET_LENGTH));

		assertThatCode(properties::validate).doesNotThrowAnyException();
		assertThat(properties.configured()).isTrue();
	}

	/** 一边的环境变量末尾多了个换行时，两边算出来的签名仍然一致 */
	@Test
	void surroundingWhitespaceIsStripped() {
		InnerProperties properties = new InnerProperties();
		properties.setSecret("  " + SECRET + "\n");
		properties.validate();

		assertThat(properties.key()).isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void clockSkewMustBePositiveAndAtMostTenMinutes() {
		InnerProperties zero = new InnerProperties();
		zero.setClockSkew(Duration.ZERO);
		assertThatThrownBy(zero::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("clock-skew");

		InnerProperties tooLong = new InnerProperties();
		tooLong.setClockSkew(Duration.ofMinutes(11));
		assertThatThrownBy(tooLong::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("clock-skew");

		InnerProperties max = new InnerProperties();
		max.setClockSkew(Duration.ofMinutes(10));
		assertThatCode(max::validate).doesNotThrowAnyException();
	}

	@Test
	void timeoutsAndSizesMustBePositive() {
		InnerProperties connect = new InnerProperties();
		connect.setConnectTimeout(Duration.ofSeconds(-1));
		assertThatThrownBy(connect::validate).hasMessageContaining("connect-timeout");

		InnerProperties read = new InnerProperties();
		read.setReadTimeout(null);
		assertThatThrownBy(read::validate).hasMessageContaining("read-timeout");

		InnerProperties longRead = new InnerProperties();
		longRead.setLongReadTimeout(Duration.ZERO);
		assertThatThrownBy(longRead::validate).hasMessageContaining("long-read-timeout");

		InnerProperties request = new InnerProperties();
		request.setMaxRequestSize(DataSize.ofBytes(0));
		assertThatThrownBy(request::validate).hasMessageContaining("max-request-size");

		InnerProperties response = new InnerProperties();
		response.setMaxResponseSize(DataSize.ofGigabytes(4));
		assertThatThrownBy(response::validate).hasMessageContaining("max-response-size");
	}

	/** 不配时：每个内部接口同时处理 5 个，满了等 2 秒；接连失败 3 次熔断，停 10 秒 */
	@Test
	void concurrencyAndBreakerDefaults() {
		InnerProperties properties = new InnerProperties();
		properties.validate();

		assertThat(properties.getMaxConcurrent()).isEqualTo(5);
		assertThat(properties.getMaxWait()).isEqualTo(Duration.ofSeconds(2));
		assertThat(properties.getBreakerFailures()).isEqualTo(3);
		assertThat(properties.getBreakerPause()).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void concurrencyAndBreakerSettingsAreChecked() {
		InnerProperties concurrent = new InnerProperties();
		concurrent.setMaxConcurrent(0);
		assertThatThrownBy(concurrent::validate).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("max-concurrent");

		InnerProperties wait = new InnerProperties();
		wait.setMaxWait(Duration.ofSeconds(-1));
		assertThatThrownBy(wait::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-wait");

		InnerProperties failures = new InnerProperties();
		failures.setBreakerFailures(-1);
		assertThatThrownBy(failures::validate).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("breaker-failures");

		InnerProperties pause = new InnerProperties();
		pause.setBreakerPause(Duration.ZERO);
		assertThatThrownBy(pause::validate).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("breaker-pause");
	}

	/** 不排队（max-wait 为 0）和不熔断（breaker-failures 为 0）都是允许的写法 */
	@Test
	void zeroWaitAndZeroFailuresAreAllowed() {
		InnerProperties properties = new InnerProperties();
		properties.setMaxWait(Duration.ZERO);
		properties.setBreakerFailures(0);

		assertThatCode(properties::validate).doesNotThrowAnyException();
	}

	/** 配置文件里的写法能绑上：密钥只有 setter 没有 getter，也要能绑 */
	@Test
	void bindsFromConfiguration() {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
			Map.entry("mugsun.cloud.inner.secret", SECRET),
			Map.entry("mugsun.cloud.inner.clock-skew", "30s"),
			Map.entry("mugsun.cloud.inner.connect-timeout", "1s"),
			Map.entry("mugsun.cloud.inner.read-timeout", "5s"),
			Map.entry("mugsun.cloud.inner.long-read-timeout", "90s"),
			Map.entry("mugsun.cloud.inner.max-request-size", "2MB"),
			Map.entry("mugsun.cloud.inner.max-response-size", "16MB"),
			Map.entry("mugsun.cloud.inner.max-concurrent", "16"),
			Map.entry("mugsun.cloud.inner.max-wait", "500ms"),
			Map.entry("mugsun.cloud.inner.breaker-failures", "5"),
			Map.entry("mugsun.cloud.inner.breaker-pause", "30s")));

		InnerProperties properties = new Binder(source).bind("mugsun.cloud.inner", InnerProperties.class).get();
		properties.validate();

		assertThat(properties.configured()).isTrue();
		assertThat(properties.key()).isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
		assertThat(properties.getClockSkew()).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(properties.getLongReadTimeout()).isEqualTo(Duration.ofSeconds(90));
		assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(2));
		assertThat(properties.getMaxResponseSize()).isEqualTo(DataSize.ofMegabytes(16));
		assertThat(properties.getMaxConcurrent()).isEqualTo(16);
		assertThat(properties.getMaxWait()).isEqualTo(Duration.ofMillis(500));
		assertThat(properties.getBreakerFailures()).isEqualTo(5);
		assertThat(properties.getBreakerPause()).isEqualTo(Duration.ofSeconds(30));
	}
}

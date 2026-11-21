package com.mugsun.cloud.gateway.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求编号的写法：哪些能沿用，网关自己生成的是什么样。规则要和 mugsun-boot 的 TraceIdFilter 一致：
 * 网关认可的编号，服务也得认，否则服务会另起一个，两边的日志就对不上了。
 */
class TraceIdsTest {

	@Test
	void lettersDigitsAndHyphensUpTo64AreAcceptable() {
		assertThat(TraceIds.acceptable("a")).isTrue();
		assertThat(TraceIds.acceptable("Order-20261121-0001")).isTrue();
		assertThat(TraceIds.acceptable("x".repeat(64))).isTrue();
	}

	@Test
	void anythingElseIsNot() {
		assertThat(TraceIds.acceptable(null)).isFalse();
		assertThat(TraceIds.acceptable("")).isFalse();
		assertThat(TraceIds.acceptable("x".repeat(65))).isFalse();
		assertThat(TraceIds.acceptable("a b")).isFalse();
		assertThat(TraceIds.acceptable("a_b")).isFalse();
		assertThat(TraceIds.acceptable("a\nb")).isFalse();
		assertThat(TraceIds.acceptable("编号")).isFalse();
		assertThat(TraceIds.acceptable("\uff11\uff12")).isFalse();
	}

	@Test
	void generatedIdsAreAcceptableAndDoNotRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			String id = TraceIds.generate();
			assertThat(id).matches("[0-9a-f]{32}");
			assertThat(TraceIds.acceptable(id)).isTrue();
			seen.add(id);
		}
		assertThat(seen).hasSize(10_000);
	}
}

package com.mugsun.cloud.gateway.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置出错时写进日志的那句话里，配置里的名字和框架给的说明要先整理：不能换行，不能无限长。
 */
class ConfigTextTest {

	@Test
	void namesKeepOnlyOrdinaryCharacters() {
		assertThat(ConfigText.name("Path")).isEqualTo("Path");
		assertThat(ConfigText.name("lanes[0].client-ips")).isEqualTo("lanes[0].client-ips");
		assertThat(ConfigText.name("a\nb c")).isEqualTo("a?b?c");
		assertThat(ConfigText.name(null)).isEqualTo("（没写）");
		assertThat(ConfigText.name("")).isEqualTo("（没写）");
		assertThat(ConfigText.name("x".repeat(200))).hasSize(83).endsWith("...");
	}

	@Test
	void textsKeepOnlyTheFirstLineWithoutControlCharacters() {
		assertThat(ConfigText.text("first line\nsecond line")).isEqualTo("first line");
		assertThat(ConfigText.text("a\tb")).isEqualTo("a b");
		assertThat(ConfigText.text(null)).isEqualTo("（没有说明）");
		assertThat(ConfigText.text("   ")).isEqualTo("（没有说明）");
		assertThat(ConfigText.text("y".repeat(500))).hasSize(203).endsWith("...");
	}
}

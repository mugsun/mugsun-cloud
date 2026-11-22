package com.mugsun.cloud.gateway.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Collections;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 读 Nacos 里的 YAML 配置时公用的两步：文本读成一行行属性；绑定失败时说清是哪几项不认识。报错都不带配置的原文。
 */
class ConfigYamlTest {

	/** 绑定用的样例：只认 name 和 count 两项 */
	static class Item {

		private String name;
		private int count;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getCount() {
			return count;
		}

		public void setCount(int count) {
			this.count = count;
		}
	}

	private static Item bind(String content) {
		Binder binder = new Binder(new MapConfigurationPropertySource(ConfigYaml.flatten(content)));
		return binder.bind("item", Bindable.of(Item.class), new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();
	}

	@Test
	void yamlIsReadIntoOnePropertyPerValue() {
		Properties flat = ConfigYaml.flatten("""
			routes:
			  - id: a
			    predicates:
			      - Path=/a/**
			group:
			  name: x
			""");

		assertThat(flat.keySet()).containsExactlyInAnyOrder("routes[0].id", "routes[0].predicates[0]", "group.name");
		assertThat(flat.getProperty("routes[0].predicates[0]")).isEqualTo("Path=/a/**");
	}

	/**
	 * 值是数字或布尔的项，读出来的值不是文字，{@code stringPropertyNames()} 里没有它们。
	 * 所以检查「有没有多写别的项」要遍历 {@code keySet()}：用前一个的话，多写一项 {@code enabled: true} 发现不了。
	 */
	@Test
	void itemsWhoseValueIsANumberOrBooleanShowUpOnlyInTheKeySet() {
		Properties flat = ConfigYaml.flatten("enabled: true\nversion: 2\nname: x\n");

		assertThat(flat.keySet()).containsExactlyInAnyOrder("enabled", "version", "name");
		assertThat(flat.stringPropertyNames()).containsExactly("name");
		assertThat(flat.get("enabled")).isEqualTo(true);
		assertThat(flat.get("version")).isEqualTo(2);
	}

	@Test
	void contentWithoutAnyValueGivesNoProperties() {
		assertThat(ConfigYaml.flatten("")).isEmpty();
		assertThat(ConfigYaml.flatten("# 只有注释\n")).isEmpty();
	}

	@Test
	void brokenYamlIsReportedByPositionWithoutQuotingTheText() {
		assertThatThrownBy(() -> ConfigYaml.flatten("item:\n  name: [x, TOP-SECRET\n"))
			.isInstanceOf(ConfigYaml.NotYamlException.class)
			.hasMessageStartingWith("第 ")
			.hasMessageContaining(" 行第 ")
			.hasMessageNotContaining("TOP-SECRET")
			.hasMessageNotContaining("\n");
	}

	/** 同一层里同一项写了两遍：不去猜该认哪一个 */
	@Test
	void sameItemTwiceInOnePlaceIsAnError() {
		assertThatThrownBy(() -> ConfigYaml.flatten("item:\n  name: x\n  name: TOP-SECRET\n"))
			.isInstanceOf(ConfigYaml.NotYamlException.class)
			.hasMessageNotContaining("TOP-SECRET");
	}

	/** 一小段文本靠层层引用展开成极大的内容：YAML 解析器自带的上限要还在 */
	@Test
	void contentThatExpandsEndlesslyIsStopped() {
		StringBuilder content = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x]\n");
		for (int level = 1; level <= 8; level++) {
			String previous = "*a" + (level - 1);
			content.append("a").append(level).append(": &a").append(level).append(" [")
				.append(String.join(", ", Collections.nCopies(9, previous))).append("]\n");
		}

		assertThatThrownBy(() -> ConfigYaml.flatten(content.toString()))
			.isInstanceOf(ConfigYaml.NotYamlException.class);
	}

	@Test
	void unknownItemsAreNamed() {
		assertThat(bind("item:\n  name: x\n  count: 2\n").getCount()).isEqualTo(2);

		assertThatThrownBy(() -> bind("item:\n  name: x\n  colour: TOP-SECRET\n"))
			.isInstanceOfSatisfying(BindException.class,
				error -> assertThat(ConfigYaml.unknownNames(error)).isEqualTo("item.colour"));
	}

	/** 不认识的项很多时只列前三个：这句话要写进日志，不能跟着配置无限变长 */
	@Test
	void atMostThreeUnknownItemsAreNamed() {
		assertThatThrownBy(() -> bind("item:\n  name: x\n  b: 1\n  c: 2\n  d: 3\n  e: 4\n"))
			.isInstanceOfSatisfying(BindException.class, error -> assertThat(ConfigYaml.unknownNames(error).split("、"))
				.hasSize(3)
				.allMatch(name -> name.matches("item\\.[bcde]")));
	}

	/** 别的绑定失败（比如该写数字的地方写了文字）不是「有不认识的项」 */
	@Test
	void otherBindingFailuresAreNotAboutUnknownItems() {
		assertThatThrownBy(() -> bind("item:\n  name: x\n  count: many\n"))
			.isInstanceOfSatisfying(BindException.class, error -> assertThat(ConfigYaml.unknownNames(error)).isNull());
	}
}

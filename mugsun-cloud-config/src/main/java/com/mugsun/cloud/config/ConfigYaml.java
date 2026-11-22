package com.mugsun.cloud.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 读运维写在 Nacos 里的 YAML 配置（动态路由、灰度规则）时公用的两步：把文本读成一行行属性，把「有不认识的配置项」说清楚。
 *
 * <p>用 Spring 自己的 YAML 读法，文档大小、嵌套层数、别名个数的上限都保留着；同一层里写了两个同名的项算错。
 * 报错里只有位置和原因，没有配置的原文。
 */
public final class ConfigYaml {

	/** 一次最多列几个不认识的配置项 */
	private static final int MAX_NAMES = 3;

	private ConfigYaml() {
	}

	/** 这份文本不是合法的 YAML。消息是「第几行第几列，什么问题」 */
	public static final class NotYamlException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		NotYamlException(String problem) {
			super(problem);
		}
	}

	/**
	 * YAML 读成「routes[0].id = ...」这样的一行行属性，和 Spring Boot 读 application.yml 的结果是同一种形状。
	 *
	 * @throws NotYamlException 不是合法的 YAML
	 */
	public static Properties flatten(String content) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
		try {
			Properties flat = yaml.getObject();
			return flat == null ? new Properties() : flat;
		} catch (RuntimeException e) {
			throw new NotYamlException(problem(e));
		}
	}

	/**
	 * 绑定失败是不是因为有不认识的配置项。是的话返回它们的名字（最多三个，顿号隔开），不是返回 null。
	 */
	public static String unknownNames(BindException error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof UnboundConfigurationPropertiesException unbound) {
				return unbound.getUnboundProperties().stream()
					.map(property -> ConfigText.name(property.getName().toString()))
					.limit(MAX_NAMES)
					.collect(Collectors.joining("、"));
			}
		}
		return null;
	}

	/** YAML 解析器的完整报错会摘录出错那一行的原文，这里只取位置和原因 */
	private static String problem(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof MarkedYAMLException marked) {
				Mark mark = marked.getProblemMark();
				String where = mark == null ? "" : "第 " + (mark.getLine() + 1) + " 行第 " + (mark.getColumn() + 1) + " 列，";
				return where + ConfigText.text(marked.getProblem());
			}
			if (cause instanceof YAMLException) {
				return ConfigText.text(cause.getMessage());
			}
		}
		return "解析时出错（" + error.getClass().getSimpleName() + "）";
	}
}

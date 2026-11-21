package com.mugsun.cloud.gateway.support;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网关的开关：application.yml 里 {@code mugsun.gateway} 下面用环境变量给值的配置项。
 * 从文件里找，不在测试里列：以后加的开关自动算进来。
 */
public final class GatewaySwitches {

	/** 可信代理的变量沿用单体版的名字，是唯一一个不按配置项起名的 */
	public static final String TRUSTED_PROXIES = "MUGSUN_OPENAPI_TRUSTED_PROXIES";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(:.*)?}");

	private GatewaySwitches() {
	}

	/** 变量名 → 它给值的配置项（{@code mugsun.gateway} 下面的名字），按文件里的顺序 */
	public static Map<String, String> declared() {
		Map<String, String> settingOfVariable = new LinkedHashMap<>();
		section(section(load(), "mugsun"), "gateway").forEach((setting, value) -> {
			if (value instanceof Map) {
				throw new IllegalStateException("mugsun.gateway." + setting + " 下面又分了一层：网关的配置项不能分组");
			}
			Matcher placeholder = PLACEHOLDER.matcher(String.valueOf(value));
			if (placeholder.matches()) {
				settingOfVariable.put(placeholder.group(1), setting);
			}
		});
		return settingOfVariable;
	}

	private static Map<String, Object> load() {
		try (InputStream in = GatewaySwitches.class.getResourceAsStream("/application.yml")) {
			return new Yaml().load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> section(Map<String, Object> parent, String name) {
		if (parent.get(name) instanceof Map<?, ?> section) {
			return (Map<String, Object>) section;
		}
		throw new IllegalStateException("application.yml 里没有找到 " + name + " 这一段");
	}
}

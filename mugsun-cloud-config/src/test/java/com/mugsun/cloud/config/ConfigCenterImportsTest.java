package com.mugsun.cloud.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 网关、各服务和运维目录下各应用的 application.yml 里，从 Nacos 配置中心读哪几份配置：名字要和各自的服务名对得上，
 * 每个应用的写法要一样。
 *
 * <p>读哪几份是写在各自文件里的（为什么不能写在共用的文件里，见 system 服务的 application.yml 开头）。照着一个服务抄出新服务时，
 * 最容易漏改的就是这几个名字：漏了不报错，新服务会去读别的服务的配置。
 *
 * <p>直接读源码目录里的文件：这个模块不依赖网关和各服务，类路径上没有它们的配置。
 */
class ConfigCenterImportsTest {

	/** 分环境的配置按这三个 profile 读 */
	private static final List<String> PROFILES = List.of("dev", "test", "prod");

	@Test
	void everyApplicationReadsTheSharedFilesAndItsOwn() throws IOException {
		List<Path> files = applicationFiles();
		assertThat(files).as("网关、四个业务服务、监控中心和日志服务，至少七份 application.yml").hasSizeGreaterThanOrEqualTo(7);

		for (Path file : files) {
			List<Map<String, Object>> documents = documents(file);
			String name = applicationName(documents);
			assertThat(name).as("%s 里的 spring.application.name", file).isNotBlank();

			for (int i = 0; i < documents.size(); i++) {
				Map<String, Object> document = documents.get(i);
				List<String> imports = imports(document);
				List<String> fromNacos = imports.stream().filter(location -> location.contains("nacos:")).toList();
				String profile = profile(document);
				if (i == 0) {
					assertThat(profile).as("%s 的第一段不分环境", file).isNull();
					assertThat(fromNacos).as("%s 第一段从 Nacos 读的：先是共用的，再是自己的", file)
						.containsExactly("optional:nacos:mugsun.yaml", "optional:nacos:" + name + ".yaml");
					// Nacos 的两份排在最后：同一段里后导入的盖过先导入的
					assertThat(imports.subList(imports.size() - 2, imports.size())).as("%s 第一段的导入顺序", file)
						.isEqualTo(fromNacos);
				}
				else if (profile != null && PROFILES.contains(profile)) {
					assertThat(fromNacos).as("%s 里 %s 那一段从 Nacos 读的：先是共用的，再是自己的", file, profile)
						.containsExactly("optional:nacos:mugsun-" + profile + ".yaml",
							"optional:nacos:" + name + "-" + profile + ".yaml");
				}
				else {
					assertThat(fromNacos).as("%s 第 %d 段不该从 Nacos 读配置", file, i + 1).isEmpty();
				}
			}
			assertThat(documents.stream().map(ConfigCenterImportsTest::profile)
				.filter(profile -> profile != null && PROFILES.contains(profile)).toList())
				.as("%s 里分环境的段：三个环境各一段", file).containsExactlyInAnyOrderElementsOf(PROFILES);
		}
	}

	/** 网关、每个服务和运维目录下每个应用的 application.yml，从仓库根目录找 */
	private static List<Path> applicationFiles() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !(Files.isDirectory(root.resolve("mugsun-cloud-gateway"))
				&& Files.isDirectory(root.resolve("mugsun-cloud-service")))) {
			root = root.getParent();
		}
		assertThat(root).as("从当前目录往上找不到仓库根目录").isNotNull();
		String settings = "src/main/resources/application.yml";
		List<Path> files = new ArrayList<>();
		files.add(root.resolve("mugsun-cloud-gateway").resolve(settings));
		for (String parent : List.of("mugsun-cloud-service", "mugsun-cloud-ops")) {
			try (Stream<Path> apps = Files.list(root.resolve(parent))) {
				apps.map(app -> app.resolve(settings)).filter(Files::isRegularFile).sorted().forEach(files::add);
			}
		}
		return files;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> documents(Path file) throws IOException {
		List<Map<String, Object>> documents = new ArrayList<>();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			for (Object document : new Yaml().loadAll(reader)) {
				if (document instanceof Map<?, ?> map) {
					documents.add((Map<String, Object>) map);
				}
			}
		}
		return documents;
	}

	private static String applicationName(List<Map<String, Object>> documents) {
		return documents.stream().map(document -> at(document, "spring", "application", "name"))
			.filter(String.class::isInstance).map(String.class::cast).findFirst().orElse(null);
	}

	private static String profile(Map<String, Object> document) {
		Object profile = at(document, "spring", "config", "activate", "on-profile");
		return profile == null ? null : profile.toString();
	}

	private static List<String> imports(Map<String, Object> document) {
		Object value = at(document, "spring", "config", "import");
		if (value instanceof Collection<?> items) {
			return items.stream().map(String::valueOf).toList();
		}
		return value == null ? List.of() : List.of(value.toString());
	}

	private static Object at(Map<String, Object> document, String... path) {
		Object current = document;
		for (String key : path) {
			if (!(current instanceof Map<?, ?> map)) {
				return null;
			}
			current = map.get(key);
		}
		return current;
	}
}

package com.mugsun.cloud.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 代码里要问「可选模块在不在」的服务，启动类要扫描 {@code com.mugsun.cloud.module}；自己就是那个模块的服务不能扫描。
 *
 * <p>漏了扫描不报错：那个服务拿到的是 mugsun-boot 给的默认实现，一直回答「不在」，编译和启动都正常，
 * 只是页面上 GIS、埋点、AI 的菜单和功能悄悄消失。把用到模块状态的类从 system 分给别的服务时最容易漏，
 * 所以按源码查：服务自己的代码（同步来的和手写的）里出现了下面这几个名字，就算用到。
 *
 * <p>直接读源码目录里的文件：这个模块不依赖各服务。
 */
class ModuleStatusScanTest {

	private static final String PACKAGE = "com.mugsun.cloud.module";

	/** 三个模块状态的接口 */
	private static final List<String> STATUSES = List.of("GisModuleStatus", "TrackModuleStatus", "AiModuleStatus");

	/** 公共层里按模块状态过滤菜单的类：用它的服务也算用到了模块状态 */
	private static final String MENU_FILTER = "ModuleMenuFilter";

	@Test
	void servicesThatAskForModuleStatusScanThePackage() throws IOException {
		List<Path> services = services();
		assertThat(services).as("带启动类和同步代码的服务").hasSizeGreaterThanOrEqualTo(4);
		int asking = 0;

		for (Path service : services) {
			String code = sources(service);
			boolean scans = Files.readString(applicationClass(service), StandardCharsets.UTF_8)
				.contains('"' + PACKAGE + '"');
			List<String> own = STATUSES.stream().filter(status -> implementsIt(code, status)).toList();
			List<String> asked = Stream.concat(STATUSES.stream(), Stream.of(MENU_FILTER))
				.filter(name -> !own.contains(name) && mentions(code, name)).toList();

			if (!own.isEmpty()) {
				assertThat(scans).as("%s 自己实现了 %s，再扫描 %s 就有两个实现，启动会失败", service, own, PACKAGE)
					.isFalse();
				assertThat(asked).as("%s 自己是可选模块，又用到了别的模块的状态：扫描 %s 会和它自己的实现冲突，要另想办法",
					service, PACKAGE).isEmpty();
			}
			else if (!asked.isEmpty()) {
				asking++;
				assertThat(scans).as("%s 的代码里用到了 %s，启动类的 @ComponentScan 里要写上 %s", service, asked, PACKAGE)
					.isTrue();
			}
		}
		assertThat(asking).as("至少 system 要用模块状态（菜单）。一个都没找到，说明上面的名字或者目录变了，这个测试没在查东西")
			.isPositive();
	}

	private static boolean mentions(String code, String name) {
		return Pattern.compile("\\b" + name + "\\b").matcher(code).find();
	}

	private static boolean implementsIt(String code, String name) {
		return Pattern.compile("\\bimplements\\s[\\w\\s,.<>]*\\b" + name + "\\b").matcher(code).find();
	}

	/** 网关和每个服务：仓库根目录和两个父目录下，既有同步来的代码、又有启动类的模块 */
	private static List<Path> services() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !(Files.isDirectory(root.resolve("mugsun-cloud-gateway"))
				&& Files.isDirectory(root.resolve("mugsun-cloud-service")))) {
			root = root.getParent();
		}
		assertThat(root).as("从当前目录往上找不到仓库根目录").isNotNull();
		List<Path> services = new ArrayList<>();
		for (Path parent : List.of(root, root.resolve("mugsun-cloud-service"), root.resolve("mugsun-cloud-ops"))) {
			try (Stream<Path> modules = Files.list(parent)) {
				modules.filter(module -> Files.isDirectory(module.resolve("src/boot/java")))
					.filter(module -> applicationClass(module) != null).sorted().forEach(services::add);
			}
		}
		return services;
	}

	private static Path applicationClass(Path module) {
		Path main = module.resolve("src/main/java");
		if (!Files.isDirectory(main)) {
			return null;
		}
		try (Stream<Path> files = Files.walk(main)) {
			return files.filter(file -> file.getFileName().toString().endsWith("Application.java")).findFirst()
				.orElse(null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 这个服务自己的代码：同步来的（src/boot）和手写的（src/main），连成一个字符串 */
	private static String sources(Path module) throws IOException {
		StringBuilder code = new StringBuilder();
		for (String folder : List.of("src/boot/java", "src/main/java")) {
			try (Stream<Path> files = Files.walk(module.resolve(folder))) {
				for (Path file : files.filter(file -> file.toString().endsWith(".java")).toList()) {
					code.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
				}
			}
		}
		return code.toString();
	}
}

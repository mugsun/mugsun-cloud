package com.mugsun.cloud.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 构建镜像时要拷进去的目录，要和根 pom 里的模块一样，并且都在放行清单里。
 *
 * <p>{@code deploy/docker/Dockerfile.dockerignore} 先把所有文件排除、再逐个目录放行，{@code Dockerfile} 再逐个目录
 * {@code COPY}。根 pom 里加一个模块时这两处都要补：漏了 {@code COPY}，镜像里打包时找不到这个模块；漏了放行，
 * 构建到 {@code COPY} 那一步报「找不到」。两种都要到真正构建镜像时才发现，平时的编译和测试不经过这两个文件。
 *
 * <p>直接读仓库里的文件，不启动 Docker。
 */
class DockerBuildContextTest {

	private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

	/** 从仓库拷一个目录进构建阶段：{@code COPY 目录/ 目录/}。带 {@code --from} 的是从别的阶段拷，不算 */
	private static final Pattern COPY_DIRECTORY = Pattern.compile("^COPY ([\\w.-]+)/ \\1/$", Pattern.MULTILINE);

	private static final Pattern ALLOWED_DIRECTORY = Pattern.compile("^!([\\w.-]+)/$", Pattern.MULTILINE);

	@Test
	void everyModuleIsCopiedIntoTheImageBuildAndAllowedIntoTheContext() throws IOException {
		Path root = repositoryRoot();
		List<String> modules = all(MODULE, root.resolve("pom.xml"));
		List<String> copied = all(COPY_DIRECTORY, root.resolve("deploy/docker/Dockerfile"));
		List<String> allowed = all(ALLOWED_DIRECTORY, root.resolve("deploy/docker/Dockerfile.dockerignore"));

		assertThat(modules).as("根 pom 里的模块").isNotEmpty();
		assertThat(copied).as("Dockerfile 里从仓库拷进构建阶段的目录，要和根 pom 里的模块一样")
			.containsExactlyInAnyOrderElementsOf(modules);
		assertThat(allowed).as("Dockerfile 里拷的目录，都要在 Dockerfile.dockerignore 里放行").containsAll(copied);
	}

	private static List<String> all(Pattern pattern, Path file) throws IOException {
		Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
		List<String> found = new ArrayList<>();
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	private static Path repositoryRoot() {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !(Files.isDirectory(root.resolve("mugsun-cloud-gateway"))
				&& Files.isDirectory(root.resolve("deploy")))) {
			root = root.getParent();
		}
		assertThat(root).as("从当前目录往上找不到仓库根目录").isNotNull();
		return root;
	}
}

package com.mugsun.cloud.config.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import com.alibaba.nacos.client.logging.NacosLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.mugsun.cloud.config.logging.LogSecretsTest.REQUEST_DUMP;
import static com.mugsun.cloud.config.logging.LogSecretsTest.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打码真的接到了日志的每个出口上：起一个真的应用，照 Nacos 的客户端的样子打一行带令牌的日志，看写出去的是什么。
 * 应用只带这个模块，用的是 Spring Boot 默认的日志配置，和网关一样；各服务用自己的 logback-spring.xml，
 * 在公共层的测试里另有一份（SecretsInLogLinesTest）。
 */
@ExtendWith(OutputCaptureExtension.class)
class LogSecretsMaskingTest {

	/** Nacos 的客户端发请求失败时用的日志类。它自带的日志配置把这个类的日志写进 remote.log */
	private static final String NACOS_REMOTE = "com.alibaba.nacos.common.remote.client";

	/** Nacos 的客户端的另一个日志类，它自带的日志配置没有管到，打出来的走控制台 */
	private static final String NACOS_OTHER = "com.alibaba.nacos.plugin.auth.spi.client";

	/** 不连 Nacos：这里只看日志怎么写出去 */
	private static final String[] ARGS = {"--spring.cloud.nacos.config.enabled=false",
			"--spring.cloud.nacos.discovery.enabled=false"};

	private final Map<String, String> systemPropertiesBefore = new HashMap<>();

	@BeforeEach
	void startFromLogbackDefaults() {
		for (String name : System.getProperties().stringPropertyNames()) {
			systemPropertiesBefore.put(name, System.getProperty(name));
		}
		// 日志系统是整个 JVM 共用的，前面的测试可能已经装过打码、用过 Nacos 的客户端。全部清掉，
		// 回到一个应用刚启动时的样子：只有 logback 默认的控制台出口，没有包过
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		for (LoggingSystemProperty property : LoggingSystemProperty.values()) {
			System.clearProperty(property.getEnvironmentVariableName());
		}
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.removeConfigurationEventListener(LogSecretsMasking.AFTER_CONFIGURED);
		// stop 连 Nacos 留在上面的监听器一起清掉；只重置的话，它一听到重置就把自己的文件出口加回来了
		context.stop();
		resetToDefaults(context);
	}

	@AfterEach
	void putLoggingBack() {
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.stop();
		resetToDefaults(context);
		for (String name : System.getProperties().stringPropertyNames()) {
			if (!systemPropertiesBefore.containsKey(name)) {
				System.clearProperty(name);
			}
		}
		systemPropertiesBefore.forEach(System::setProperty);
	}

	private static void resetToDefaults(LoggerContext context) {
		try {
			context.reset();
			new ContextInitializer(context).autoConfig();
			context.start();
		}
		catch (Exception ex) {
			throw new IllegalStateException("logback 回不到默认配置", ex);
		}
	}

	/** 网关和各服务都不扫描这个包，靠登记生效。登记漏了没有任何报错，只是日志里又有令牌了 */
	@Test
	void registeredForEveryApplication() {
		assertThat(SpringFactoriesLoader.loadFactories(ApplicationListener.class, getClass().getClassLoader()))
			.hasAtLeastOneElementOfType(LogSecretsMasking.class);
	}

	/**
	 * 应用运行中打的日志。Spring Boot 默认的日志配置是用代码配的，logback 不发通知，
	 * 靠的是排在 Spring Boot 初始化日志之后的那一遍。
	 */
	@Test
	void consoleOfARunningApplication(CapturedOutput output) {
		try (ConfigurableApplicationContext context = application().run(ARGS)) {
			Logger nacos = LoggerFactory.getLogger(NACOS_REMOTE);
			nacos.error(REQUEST_DUMP);
			// 令牌在异常的消息里，跟着堆栈一起打出来
			nacos.error("注销实例失败", new IllegalStateException(REQUEST_DUMP));
		}

		assertThat(output.getAll()).doesNotContain(TOKEN);
		assertThat(output.getOut().lines().filter(line -> line.contains("headers={accessToken=***, app=unknown}")))
			.as("控制台上令牌换成了星号的行：日志的消息一行，异常的消息一行")
			.hasSize(2);
	}

	/**
	 * Spring Boot 初始化日志之前，应用已经在从 Nacos 读配置了。这一段里 Nacos 的客户端的日志有两个去处：
	 * 它自带的日志配置管到的写进它自己的文件（remote.log 等），没管到的从 logback 默认的控制台出口出去。两处都要打码。
	 */
	@Test
	void linesWrittenBeforeLoggingIsSetUp(CapturedOutput output) throws Exception {
		String marker = "early-" + UUID.randomUUID();
		AtomicReference<String> nacosLogFile = new AtomicReference<>();
		SpringApplication application = application();
		application.addListeners(new FirstUseOfTheNacosClient(marker, nacosLogFile));

		application.run(ARGS).close();

		assertThat(output.getAll()).doesNotContain(TOKEN);
		assertThat(output.getOut().lines().filter(line -> line.contains(marker + " console")))
			.as("控制台上那一行")
			.singleElement().asString().contains("headers={accessToken=***, app=unknown}");
		// 这个文件不归测试管，里面可能有以前留下的内容，只看这一次打的那一行
		List<String> written = Files.readAllLines(Path.of(nacosLogFile.get()), StandardCharsets.UTF_8).stream()
			.filter(line -> line.contains(marker + " file")).toList();
		assertThat(written).as("Nacos 自己的日志文件 %s 里那一行", nacosLogFile.get())
			.singleElement().asString().contains("headers={accessToken=***, app=unknown}").doesNotContain(TOKEN);
	}

	private static SpringApplication application() {
		SpringApplication application = new SpringApplication(Bare.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setBannerMode(Banner.Mode.OFF);
		application.setRegisterShutdownHook(false);
		return application;
	}

	@Configuration(proxyBeanMethods = false)
	static class Bare {
	}

	/**
	 * 应用从 Nacos 读配置的那一步：排在 Spring Boot 初始化日志之前。Nacos 的客户端第一次被用到，
	 * 先加载自带的日志配置（调的是它自己的入口），然后就开始打日志。
	 */
	private record FirstUseOfTheNacosClient(String marker, AtomicReference<String> nacosLogFile)
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

		@Override
		public int getOrder() {
			return LoggingApplicationListener.DEFAULT_ORDER - 1;
		}

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			ch.qos.logback.classic.Logger remote = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NACOS_REMOTE);
			assertThat(fileBehind(remote)).as("应用刚启动时还没有 Nacos 的日志文件出口").isNull();

			NacosLogging.getInstance().loadConfiguration();

			String file = fileBehind(remote);
			assertThat(file).as("Nacos 的客户端没有给自己的日志加文件出口：它的做法变了，LogSecretsMasking 的说明和这个测试都要重新看")
				.isNotNull();
			nacosLogFile.set(file);
			remote.error(marker + " file " + REQUEST_DUMP);
			LoggerFactory.getLogger(NACOS_OTHER).error(marker + " console " + REQUEST_DUMP);
		}

		/** 这个日志类的日志写进哪个文件。Nacos 在文件出口前面加了一层异步的，要看到它后面 */
		private static String fileBehind(ch.qos.logback.classic.Logger logger) {
			Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders();
			while (appenders.hasNext()) {
				if (appenders.next() instanceof AppenderAttachable<?> async) {
					Iterator<? extends Appender<?>> behind = async.iteratorForAppenders();
					while (behind.hasNext()) {
						if (behind.next() instanceof FileAppender<?> file) {
							return file.getFile();
						}
					}
				}
			}
			return null;
		}
	}
}

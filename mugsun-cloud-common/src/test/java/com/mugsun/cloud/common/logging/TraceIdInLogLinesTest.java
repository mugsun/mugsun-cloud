package com.mugsun.cloud.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import com.mugsun.boot.common.constant.MonitorConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务的每一行日志在级别后面带着请求编号，控制台和日志文件都是：拿着网关给的编号，要能在服务的日志里找到这一次请求。
 * 走的是服务启动时的同一条路：照各服务 application.yml 的顺序导入两份默认配置，交给 Spring Boot 初始化日志，
 * 用的日志配置文件就是类路径上那份 logback-spring.xml。
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceIdInLogLinesTest {

	private static final String SERVICE = "mugsun-log-probe";

	private static final String TRACE_ID = "3f6c1d0a-trace-in-log-lines";

	@TempDir
	Path logDir;

	private final Map<String, String> systemPropertiesBefore = new HashMap<>();

	@BeforeEach
	void forgetEarlierLoggingSetup() {
		for (String name : System.getProperties().stringPropertyNames()) {
			systemPropertiesBefore.put(name, System.getProperty(name));
		}
		// 日志系统是整个 JVM 共用的。前面的测试要是初始化过它，Spring Boot 不会再做一遍，
		// 它写进系统属性的日志格式也不会被新的值覆盖。两样都先清掉，这里才是从头初始化
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		for (LoggingSystemProperty property : LoggingSystemProperty.values()) {
			System.clearProperty(property.getEnvironmentVariableName());
		}
	}

	@AfterEach
	void putLoggingBack() throws Exception {
		MDC.remove(MonitorConstants.TRACE_MDC_KEY);
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		// 关掉这次打开的日志文件，回到没有经过 Spring Boot 初始化时的样子，后面的测试照常打日志
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.reset();
		new ContextInitializer(context).autoConfig();
		for (String name : System.getProperties().stringPropertyNames()) {
			if (!systemPropertiesBefore.containsKey(name)) {
				System.clearProperty(name);
			}
		}
		systemPropertiesBefore.forEach(System::setProperty);
	}

	@Test
	void consoleAndFileLinesCarryTheTraceId(CapturedOutput output) throws Exception {
		initializeLoggingTheWayAServiceDoes();
		Logger logger = LoggerFactory.getLogger(TraceIdInLogLinesTest.class);

		MDC.put(MonitorConstants.TRACE_MDC_KEY, TRACE_ID);
		logger.info("logged-inside-a-request");
		MDC.remove(MonitorConstants.TRACE_MDC_KEY);
		logger.info("logged-outside-a-request");

		String console = output.getOut();
		// 日志文件按服务名分开
		String file = Files.readString(logDir.resolve(SERVICE + ".log"), StandardCharsets.UTF_8);

		assertThat(lineWith(console, "logged-inside-a-request")).as("控制台，在请求里打的日志")
			.contains(" INFO [" + TRACE_ID + "]");
		assertThat(lineWith(file, "logged-inside-a-request")).as("日志文件，在请求里打的日志")
			.contains(" INFO [" + TRACE_ID + "]");
		// 不在请求里打的日志（启动、定时任务），这一格是空的
		assertThat(lineWith(console, "logged-outside-a-request")).as("控制台，不在请求里打的日志")
			.contains(" INFO []");
		assertThat(lineWith(file, "logged-outside-a-request")).as("日志文件，不在请求里打的日志")
			.contains(" INFO []");
	}

	private void initializeLoggingTheWayAServiceDoes() {
		ConfigurableEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
			// 只读下面导入的两个文件，不去找类路径上别的 application*.yml
			"spring.config.location", "optional:classpath:/no-such-directory/",
			// 顺序和各服务 application.yml 第一段的一样
			"spring.config.import", "classpath:mugsun-boot-defaults.yml,classpath:mugsun-cloud-defaults.yml",
			"spring.application.name", SERVICE,
			"logging.file.path", logDir.toString())));
		ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), null);

		SpringApplication application = new SpringApplication();
		DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();
		LoggingApplicationListener listener = new LoggingApplicationListener();
		listener.onApplicationEvent(new ApplicationStartingEvent(bootstrapContext, application, new String[0]));
		listener.onApplicationEvent(
			new ApplicationEnvironmentPreparedEvent(bootstrapContext, application, new String[0], environment));
	}

	private static String lineWith(String lines, String marker) {
		return lines.lines().filter(line -> line.contains(marker)).findFirst()
			.orElseThrow(() -> new AssertionError("没有找到带 " + marker + " 的那一行日志"));
	}
}

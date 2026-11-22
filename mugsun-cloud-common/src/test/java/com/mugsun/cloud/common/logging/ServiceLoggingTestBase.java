package com.mugsun.cloud.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import com.mugsun.cloud.config.logging.LogSecretsMasking;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 看服务的日志写出去是什么样的测试共用的部分：照服务启动的样子把日志初始化一遍，测完放回原样。
 * 走的是服务启动时的同一条路：照各服务 application.yml 的顺序导入两份默认配置，交给 Spring Boot 初始化日志，
 * 用的日志配置文件就是类路径上那份 logback-spring.xml；应用启动时和日志有关的监听器，照它们登记的先后各走一遍。
 */
@ExtendWith(OutputCaptureExtension.class)
abstract class ServiceLoggingTestBase {

	static final String SERVICE = "mugsun-log-probe";

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

	void initializeLoggingTheWayAServiceDoes() {
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
		List<ApplicationListener<ApplicationEvent>> listeners = loggingListeners();
		for (ApplicationEvent event : List.of(new ApplicationStartingEvent(bootstrapContext, application, new String[0]),
				new ApplicationEnvironmentPreparedEvent(bootstrapContext, application, new String[0], environment))) {
			listeners.forEach(listener -> listener.onApplicationEvent(event));
		}
	}

	/**
	 * 应用启动时和日志有关的两个监听器：Spring Boot 初始化日志的，和给日志打码的。从登记的地方取（spring.factories），
	 * 取出来已经按它们自己声明的先后排好：打码的没登记，这里就没有它，和真的服务一样。
	 */
	@SuppressWarnings("unchecked")
	private List<ApplicationListener<ApplicationEvent>> loggingListeners() {
		return SpringFactoriesLoader.loadFactories(ApplicationListener.class, getClass().getClassLoader()).stream()
			.filter(listener -> listener instanceof LoggingApplicationListener || listener instanceof LogSecretsMasking)
			.map(listener -> (ApplicationListener<ApplicationEvent>) listener)
			.toList();
	}

	/** 日志目录下的一个日志文件的全部内容 */
	String logFile(String name) throws Exception {
		return Files.readString(logDir.resolve(name), StandardCharsets.UTF_8);
	}
}

package com.mugsun.cloud.common.inner;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InnerMappingGuardTest {

	@RestController
	@RequestMapping("/system/sample")
	static class OrdinaryController {

		@GetMapping("/list")
		String list() {
			return "ok";
		}

		/** 名字以 inner 开头的另一个路径段，不算落在 /inner 下 */
		@GetMapping("/innermost")
		String innermost() {
			return "ok";
		}
	}

	@RestController
	static class InnerishController {

		@GetMapping({"/innerx/list", "/inner-api/list", "/inner_api/list"})
		String list() {
			return "ok";
		}
	}

	@RestController
	@RequestMapping("/inner/gis")
	static class MisplacedController {

		@PostMapping("/snapshot")
		String snapshot() {
			return "should never be reachable";
		}
	}

	@RestController
	static class WildcardController {

		@GetMapping("/inner/**")
		String anything() {
			return "should never be reachable";
		}
	}

	@Test
	void ordinaryControllersStartNormally() {
		assertThatCode(() -> start(OrdinaryController.class, InnerishController.class)).doesNotThrowAnyException();
	}

	@Test
	void controllerUnderInnerRefusesToStart() {
		assertThatThrownBy(() -> start(OrdinaryController.class, MisplacedController.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("/inner/gis/snapshot")
			.hasMessageContaining("MisplacedController#snapshot")
			.hasMessageContaining("InnerEndpoint");
	}

	@Test
	void wildcardUnderInnerRefusesToStart() {
		assertThatThrownBy(() -> start(WildcardController.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("/inner/**");
	}

	@Test
	void underInnerLooksAtTheFirstPathSegment() {
		assertThat(List.of("/inner", "/inner/", "/inner/x", "/inner/**", "/inner*", "/inner{x}", "/inner.json", "inner/x",
			"/inner?"))
			.allMatch(InnerMappingGuard::underInner);
		assertThat(List.of("/innerx", "/inner-x", "/inner_x", "/inner2", "/api/inner/x", "/system/inner", "/", ""))
			.noneMatch(InnerMappingGuard::underInner);
	}

	private static void start(Class<?>... controllers) {
		try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
			context.setServletContext(new MockServletContext());
			context.register(RequestMappingHandlerMapping.class, InnerMappingGuard.class);
			context.register(controllers);
			context.refresh();
		}
	}
}

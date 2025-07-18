/*
 * Copyright 2013-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.test.LocalServerPort;
import org.springframework.boot.web.server.test.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.test.ConfigServerTestUtils;
import org.springframework.cloud.config.server.test.TestConfigServerApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.cloud.config.server.test.ConfigServerTestUtils.getV2AcceptEntity;

@SpringBootTest(classes = TestConfigServerApplication.class,
		properties = { "spring.config.name:configserver",
				"spring.cloud.config.server.git.uri:file:./target/repos/config-repo" },
		webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
public class VanillaConfigServerIntegrationTests {

	@LocalServerPort
	private int port;

	@BeforeAll
	public static void init() throws IOException {
		// mock Git configuration to make tests independent of local Git configuration
		SystemReader.setInstance(new MockSystemReader());

		ConfigServerTestUtils.prepareLocalRepo();
	}

	@Test
	public void contextLoads() {
		ResponseEntity<Environment> response = new TestRestTemplate().exchange(
				"http://localhost:" + this.port + "/foo/development", HttpMethod.GET, getV2AcceptEntity(),
				Environment.class);
		Environment environment = response.getBody();
		assertThat(environment.getPropertySources()).isNotEmpty();
		assertThat(environment.getPropertySources().get(0).getName()).isEqualTo("overrides");
		ConfigServerTestUtils.assertConfigEnabled(environment);
	}

	@Test
	public void resourseEndpointsWork() {
		String text = new TestRestTemplate()
			.getForObject("http://localhost:" + this.port + "/foo/development/master/bar.properties", String.class);

		String expected = "foo: bar";
		assertThat(text).as("invalid content").isEqualTo(expected);

		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM));
		ResponseEntity<byte[]> response = new TestRestTemplate().exchange(
				"http://localhost:" + this.port + "/foo/development/raw/bar.properties", HttpMethod.GET,
				new HttpEntity<>(headers), byte[].class);
		// FIXME: this is calling the text endpoint, not the binary one
		// assertTrue("invalid content type",
		// response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM));
		assertThat(response.getBody().length).isEqualTo(expected.length());
	}

	@Test
	public void invalidYaml() {
		ResponseEntity<Environment> response = new TestRestTemplate().exchange(
				"http://localhost:" + this.port + "/invalid/default", HttpMethod.GET, getV2AcceptEntity(),
				Environment.class);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
	}

}

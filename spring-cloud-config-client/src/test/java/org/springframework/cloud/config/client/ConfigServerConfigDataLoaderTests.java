/*
 * Copyright 2013-2023 the original author or authors.
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

package org.springframework.cloud.config.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.config.client.ConfigClientProperties.AUTHORIZATION;
import static org.springframework.cloud.config.environment.EnvironmentMediaType.V2_JSON;

/**
 * Unit Test for {@link ConfigServerConfigDataLoader}.
 *
 * <p>
 * This test was based on {@link ConfigServicePropertySourceLocatorTests}. The
 * {@link ConfigServicePropertySourceLocator} is used only when legacy bootstrap is in
 * use. Otherwise, {@link ConfigServerConfigDataLoader} is used.
 * </p>
 *
 * @author Marnee DeRider
 */
public class ConfigServerConfigDataLoaderTests {

	private static final Log logger = LogFactory.getLog(ConfigServerConfigDataLoaderTests.class);

	private static final String LABEL = "main";

	private static final String NAME = "application";

	private static final String PROFILES = "dev";

	private static final String URI_TEMPLATE = "%s/%s/%s/%s";

	@Captor
	private ArgumentCaptor<HttpEntity<Void>> httpEntityArgumentCaptor;

	private ConfigurableBootstrapContext bootstrapContext;

	private ConfigDataLoaderContext context;

	private ConfigurableEnvironment environment;

	private ConfigServerConfigDataLoader loader;

	private ConfigClientProperties properties;

	private ConfigServerConfigDataResource resource;

	private RestTemplate restTemplate;

	@BeforeEach
	public void init() {
		MockitoAnnotations.openMocks(this);

		environment = new StandardEnvironment();
		loader = new ConfigServerConfigDataLoader(destination -> logger);
		restTemplate = mock(RestTemplate.class);
		context = mock(ConfigDataLoaderContext.class);
		bootstrapContext = mock(ConfigurableBootstrapContext.class);
		resource = mock(ConfigServerConfigDataResource.class);
		properties = new ConfigClientProperties(this.environment);

		properties.setName(NAME);
		properties.setLabel(LABEL);

		when(context.getBootstrapContext()).thenReturn(bootstrapContext);
		when(bootstrapContext.get(ConfigClientRequestTemplateFactory.class))
			.thenReturn(mock(ConfigClientRequestTemplateFactory.class));
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);
		when(resource.getProperties()).thenReturn(properties);
		when(resource.isOptional()).thenReturn(true);

		when(resource.getProfiles()).thenReturn(PROFILES);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void sunnyDayWithoutLabel() {
		Environment body = new Environment("app", "master");
		mockRequestResponseWithoutLabel(new ResponseEntity<>(body, HttpStatus.OK));

		properties.setLabel(null);

		assertThat(this.loader.load(context, resource)).isNotNull();

		Mockito.verify(this.restTemplate)
			.exchange(anyString(), any(HttpMethod.class), httpEntityArgumentCaptor.capture(), any(Class.class),
					anyString(), anyString());

		HttpEntity<Void> httpEntity = httpEntityArgumentCaptor.getValue();
		assertThat(httpEntity.getHeaders().getAccept()).containsExactly(MediaType.parseMediaType(V2_JSON));
	}

	@Test
	public void customMediaType() {
		Environment body = new Environment("app", "master");
		mockRequestResponseWithoutLabel(new ResponseEntity<>(body, HttpStatus.OK));
		properties.setMediaType("application/json");
		properties.setLabel(null);

		assertThat(loader.load(context, resource)).isNotNull();

		Mockito.verify(this.restTemplate)
			.exchange(anyString(), any(HttpMethod.class), httpEntityArgumentCaptor.capture(),
					ArgumentMatchers.<Class<Environment>>any(), anyString(), anyString());

		HttpEntity<Void> httpEntity = httpEntityArgumentCaptor.getValue();
		assertThat(httpEntity.getHeaders().getAccept()).containsExactly(MediaType.parseMediaType("application/json"));
	}

	@Test
	public void sunnyDayWithLabel() {
		Environment body = new Environment("app", "master");
		properties.setLabel("v1.0.0");
		mockRequestResponseWithLabel(new ResponseEntity<>(body, HttpStatus.OK), "v1.0.0");
		TestPropertyValues.of("spring.cloud.config.label:v1.0.0").applyTo(this.environment);
		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	@Test
	public void sunnyDayWithLabelThatContainsASlash() {
		Environment body = new Environment("app", "master");
		String label = "release(_)v1.0.0";
		mockRequestResponseWithLabel(new ResponseEntity<>(body, HttpStatus.OK), label);
		properties.setLabel(label);
		TestPropertyValues.of("spring.cloud.config.label:release/v1.0.0").applyTo(this.environment);
		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	@Test
	public void failFast() throws Exception {
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		mockRequestResponse(requestFactory, null, HttpStatus.INTERNAL_SERVER_ERROR);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		properties.setFailFast(true);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);
		assertThatExceptionOfType(ConfigClientFailFastException.class)
			.isThrownBy(() -> this.loader.load(context, resource))
			.withMessageContaining("fail fast property is set")
			.withCauseInstanceOf(HttpServerErrorException.class);
	}

	@Test
	public void failFastWhenNotFound() throws Exception {
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		mockRequestResponse(requestFactory, null, HttpStatus.NOT_FOUND);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		properties.setFailFast(true);
		properties.setLabel("WeSetUpToReturn_NOT_FOUND_ForThisLabel");
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);
		assertThatExceptionOfType(ConfigClientFailFastException.class)
			.isThrownBy(() -> this.loader.load(context, resource))
			.withMessageContaining(
					"fail fast property is set, failing: None of labels [WeSetUpToReturn_NOT_FOUND_ForThisLabel] found");
	}

	@Test
	public void failFastWhenRequestTimesOut() {
		mockRequestTimedOut();
		properties.setFailFast(true);
		assertThatExceptionOfType(ConfigClientFailFastException.class)
			.isThrownBy(() -> this.loader.load(context, resource))
			.withMessageContaining("fail fast property is set")
			.withCauseInstanceOf(ResourceAccessException.class);

	}

	@Test
	public void failFastWhenBothPasswordAndAuthorizationPropertiesSet() throws Exception {
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		ClientHttpRequest request = mock(ClientHttpRequest.class);
		when(requestFactory.createRequest(any(URI.class), any(HttpMethod.class))).thenReturn(request);
		properties.setFailFast(true);
		properties.setUsername("username");
		properties.setPassword("password");
		properties.getHeaders().put(AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQNCg==");
		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.loader.load(context, resource))
			.withMessageContaining("Could not locate PropertySource and the fail fast property is set, failing");
	}

	@Test
	public void interceptorShouldAddHeadersWhenHeadersPropertySet() throws Exception {
		MockClientHttpRequest request = new MockClientHttpRequest();
		ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
		byte[] body = new byte[] {};
		Map<String, String> headers = new HashMap<>();
		headers.put("X-Example-Version", "2.1");
		new ConfigClientRequestTemplateFactory.GenericRequestHeaderInterceptor(headers).intercept(request, body,
				execution);
		Mockito.verify(execution).execute(request, body);
		assertThat(request.getHeaders().getFirst("X-Example-Version")).isEqualTo("2.1");
	}

	@Test
	public void shouldAddAuthorizationHeaderWhenPasswordSet() {
		HttpHeaders headers = new HttpHeaders();
		String username = "user";
		String password = "pass";
		factory(properties).addAuthorizationToken(headers, username, password);
		assertThat(headers.size()).isEqualTo(1);
	}

	@Test
	public void shouldAddAuthorizationHeaderWhenAuthorizationSet() {
		HttpHeaders headers = new HttpHeaders();
		properties.getHeaders().put(AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQNCg==");
		String username = "user";
		factory(properties).addAuthorizationToken(headers, username, null);
		assertThat(headers.size()).isEqualTo(1);
	}

	@Test
	public void shouldThrowExceptionWhenPasswordAndAuthorizationBothSet() {
		HttpHeaders headers = new HttpHeaders();
		properties.getHeaders().put(AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQNCg==");
		String username = "user";
		String password = "pass";
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> factory(properties).addAuthorizationToken(headers, username, password))
			.withMessageContaining("You must set either 'password' or 'authorization'");
	}

	@Test
	public void shouldThrowExceptionWhenNegativeReadTimeoutSet() {
		properties.setRequestReadTimeout(-1);
		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> factory(properties).create())
			.withMessageContaining("Invalid Value for Read Timeout set.");

	}

	@Test
	public void shouldThrowExceptionWhenNegativeConnectTimeoutSet() {
		properties.setRequestConnectTimeout(-1);
		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> factory(properties).create())
			.withMessageContaining("Invalid Value for Connect Timeout set.");
	}

	@Test
	public void shouldNotUseNextUriFor_400_And_CONNECTION_TIMEOUT_ONLY_Strategy() throws Exception {
		assertNextUriIsNotTriedForClientError(ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY,
				HttpStatus.BAD_REQUEST);
	}

	@Test
	public void shouldUseNextUriFor_400_And_ALWAYS_Strategy() throws Exception {
		assertNextUriIsTried(ConfigClientProperties.MultipleUriStrategy.ALWAYS, HttpStatus.BAD_REQUEST);
	}

	@Test
	public void shouldNotUseNextUriFor_404_And_CONNECTION_TIMEOUT_ONLY_Strategy() throws Exception {
		assertNextUriIsNotTriedForNotFoundError(ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY,
				HttpStatus.NOT_FOUND);
	}

	@Test
	public void shouldUseNextUriFor_404_And_ALWAYS_Strategy() throws Exception {
		assertNextUriIsTried(ConfigClientProperties.MultipleUriStrategy.ALWAYS, HttpStatus.NOT_FOUND);
	}

	@Test
	public void shouldNotUseNextUriFor_500_And_CONNECTION_TIMEOUT_ONLY_Strategy() throws Exception {
		assertNextUriIsNotTriedForServerError(ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY,
				HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	public void shouldUseNextUriFor_500_And_ALWAYS_Strategy() throws Exception {
		assertNextUriIsTried(ConfigClientProperties.MultipleUriStrategy.ALWAYS, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	public void shouldUseNextUriFor_NoExceptionNotOK_And_CONNECTION_TIMEOUT_ONLY_Strategy_FailFastIsFalse()
			throws Exception {
		// At the time of this writing, TEMPORARY_REDIRECT will not cause an exception to
		// be thrown back to
		// getRemoteEnvironment, but since status is not OK, the method returns null and
		// locate method will
		// simply return null since fail-fast=false. (Second URL is never tried, due to
		// the strategy.

		// Set up with two URIs.
		String badURI = "http://baduri";
		String goodURI = "http://localhost:8888";
		String[] uris = new String[] { badURI, goodURI };
		properties.setUri(uris);
		properties.setFailFast(false);
		// Strategy is CONNECTION_TIMEOUT_ONLY, so it should not try the next URI for
		// TEMPORARY_REDIRECT
		properties.setMultipleUriStrategy(ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		mockRequestResponse(requestFactory, badURI, HttpStatus.TEMPORARY_REDIRECT);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);
		assertThat(this.loader.load(context, resource)).isNull();
	}

	@Test
	public void shouldUseNextUriFor_NoExceptionNotOK_And_CONNECTION_TIMEOUT_ONLY_Strategy_WithFailFastIsTrue()
			throws Exception {
		// At the time of this writing, TEMPORARY_REDIRECT will not cause an exception to
		// be thrown back to
		// getRemoteEnvironment, but since status is not OK, the method returns null and
		// locate method will
		// throw an IllegalStateException with no cause, since fail-fast=true. Second URL
		// is never tried, due to the strategy.
		assertNextUriIsNotTried(true, ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY,
				HttpStatus.TEMPORARY_REDIRECT, null // IllegalStateException has no cause,
		// because getRemoteEnvironment did
		// not throw an exception
		);
	}

	@Test
	public void shouldUseNextUriFor_NoExceptionNotOK_And_ALWAYS_Strategy() throws Exception {
		// At the time of this writing, TEMPORARY_REDIRECT will not cause an exception to
		// be thrown back to
		// getRemoteEnvironment, but since status is not OK, the method will treat it the
		// same as an exception and
		// thus try the next URL.
		assertNextUriIsTried(ConfigClientProperties.MultipleUriStrategy.ALWAYS, HttpStatus.TEMPORARY_REDIRECT);
	}

	@Test
	public void shouldUseNextUriFor_TimeOut_And_ALWAYS_Strategy() throws Exception {
		assertNextUriIsTriedOnTimeout(ConfigClientProperties.MultipleUriStrategy.ALWAYS);
	}

	@Test
	public void shouldUseNextUriFor_TimeOut_And_CONNECTION_TIMEOUT_ONLY_Strategy() throws Exception {
		assertNextUriIsTriedOnTimeout(ConfigClientProperties.MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY);
	}

	@Test
	public void shouldNotUseNextUriWhenOneIsSuccessful() throws Exception {
		// Set up with three URIs.
		String badURI1 = "http://baduri1";
		String goodURI = "http://localhost:8888";
		String badURI2 = "http://baduri2";
		String[] uris = new String[] { badURI1, goodURI, badURI2 };
		properties.setUri(uris);
		properties.setFailFast(true);
		properties.setMultipleUriStrategy(ConfigClientProperties.MultipleUriStrategy.ALWAYS);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);

		// Second URI will be successful, and the third one should never be called, so
		// locateCollection
		// should return a value.
		mockRequestResponse(requestFactory, badURI1, HttpStatus.BAD_REQUEST);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);
		mockRequestResponse(requestFactory, badURI2, HttpStatus.INTERNAL_SERVER_ERROR);

		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	@Test
	public void shouldUseMultipleURIs() throws Exception {
		// Set up with four URIs. All should be called until one is successful
		String badURI1 = "http://baduri1";
		String badURI2 = "http://baduri2";
		String badURI3 = "http://baduri3";
		String goodURI = "http://localhost:8888";
		String[] uris = new String[] { badURI1, goodURI, badURI2 };
		properties.setUri(uris);
		properties.setFailFast(true);
		properties.setMultipleUriStrategy(ConfigClientProperties.MultipleUriStrategy.ALWAYS);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);

		// Second URI will be successful, and the third one should never be called, so
		// locateCollection
		// should return a value.
		mockRequestResponse(requestFactory, badURI1, HttpStatus.BAD_REQUEST);
		mockRequestResponse(requestFactory, badURI2, HttpStatus.INTERNAL_SERVER_ERROR);
		mockRequestResponse(requestFactory, badURI3, HttpStatus.NOT_FOUND);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);

		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	@Test
	void nonProfileSpecific() {
		PropertySource p1 = new PropertySource("p1", new HashMap<>());
		PropertySource p2 = new PropertySource("p2", new HashMap<>());
		ConfigData configData = setupConfigServerConfigDataLoader(Arrays.asList(p1, p2), "application-slash", null);
		assertThat(configData.getPropertySources()).hasSize(3);
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.IGNORE_IMPORTS)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.IGNORE_IMPORTS)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(2))
			.contains(ConfigData.Option.IGNORE_IMPORTS)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(2))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();

	}

	@Test
	public void useDiscoveryUriIfEnabled() throws Exception {
		String[] uris = new String[] { "http://uritest:8888" };
		properties.setUri(uris);
		ConfigClientProperties.Discovery discovery = new ConfigClientProperties.Discovery();
		discovery.setEnabled(true);
		discovery.setServiceId("configservice");
		properties.setDiscovery(discovery);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);
		ConfigClientProperties bootstrapConfigClientProperties = new ConfigClientProperties();
		bootstrapConfigClientProperties.setDiscovery(discovery);
		bootstrapConfigClientProperties.setUri(new String[] { "http://configservice:8888" });
		when(bootstrapContext.get(ConfigClientProperties.class)).thenReturn(bootstrapConfigClientProperties);

		mockRequestResponse(requestFactory, "http://configservice:8888", HttpStatus.OK);

		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	@Disabled
	@Test
	// TODO Enable once we have
	// https://github.com/spring-cloud/spring-cloud-config/issues/2291
	void filterPropertySourcesThatAreNotProfileSpecific() {
		PropertySource p1 = new PropertySource("p1", Collections.singletonMap("foo", "bar"));
		PropertySource p2 = new PropertySource("p2", Collections.singletonMap("hello", "world"));
		ConfigData configData = setupConfigServerConfigDataLoader(Arrays.asList(p1, p2), "application-slash", "dev");
		assertThat(configData.getPropertySources()).isEmpty();

	}

	@Disabled
	@Test
	// TODO Enable once we have
	// https://github.com/spring-cloud/spring-cloud-config/issues/2291
	void returnPropertySourcesThatAreProfileSpecific() {
		PropertySource p1 = new PropertySource("p1-dev", Collections.singletonMap("foo", "bar"));
		PropertySource p2 = new PropertySource("p2-dev", Collections.singletonMap("hello", "world"));
		List<PropertySource> propertySources = Arrays.asList(p1, p2);
		ConfigData configData = setupConfigServerConfigDataLoader(propertySources, "application-slash", "dev");
		assertThat(configData.getPropertySources()).hasSize(2);
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.IGNORE_IMPORTS)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.IGNORE_IMPORTS)).isTrue();

	}

	@Disabled
	@Test
	// TODO Enable once we have
	// https://github.com/spring-cloud/spring-cloud-config/issues/2291
	void filterPropertySourcesWithDocuments() {
		PropertySource p1 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/application.yml",
				Collections.singletonMap("foo", "bar"));
		PropertySource p2 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/application-foo.yml",
				Collections.singletonMap("foo", "bar"));
		PropertySource p3 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #0)",
				Collections.singletonMap("hello", "world"));
		PropertySource p4 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application-foo.properties' via location 'commons/' (document #0)",
				Collections.singletonMap("hello", "world"));
		Map<String, Object> activatesOnProfileCamelCase = new HashMap<>();
		activatesOnProfileCamelCase.put("spring.config.activate.onProfile", "foo");
		PropertySource p5 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #1)",
				activatesOnProfileCamelCase);
		Map<String, Object> activatesOnProfile = new HashMap<>();
		activatesOnProfile.put("spring.config.activate.on-profile", "foo");
		PropertySource p6 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #2)",
				activatesOnProfile);
		PropertySource p7 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/application-foo.yaml",
				Collections.singletonMap("hello", "world"));
		PropertySource p8 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application-foo.yaml' via location 'commons/' (document #0)",
				Collections.singletonMap("hello", "world"));
		PropertySource p9 = new PropertySource(
				"configserver:git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application-foo.yaml' via location 'commons/' (document #1)",
				Collections.singletonMap("hello", "world"));

		ConfigData configData = setupConfigServerConfigDataLoader(Arrays.asList(p1, p2, p3, p4, p5, p6, p7, p8, p9),
				"application-slash", "foo");
		assertThat(configData.getPropertySources()).hasSize(7);

	}

	@Test
	void testProfileSpecificPropertySources() {
		PropertySource p1 = new PropertySource("overrides", Collections.singletonMap("foo", "bar"));
		PropertySource p2 = new PropertySource("classpath:/test-default/config-client/application.yaml",
				Collections.singletonMap("foo", "baroverride"));
		PropertySource p3 = new PropertySource(
				"git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #0)",
				Collections.singletonMap("hello", "world"));
		PropertySource p4 = new PropertySource("aws:secrets:/secret/application-name_profile",
				Collections.singletonMap("hello", "world"));
		Map<String, Object> activatesOnProfileCamelCase = new HashMap<>();
		activatesOnProfileCamelCase.put("spring.config.activate.onProfile", "foo");
		PropertySource p5 = new PropertySource(
				"git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #1)",
				activatesOnProfileCamelCase);
		Map<String, Object> activatesOnProfile = new HashMap<>();
		activatesOnProfile.put("spring.config.activate.on-profile", "foo");
		PropertySource p6 = new PropertySource(
				"ssh://git@stash.int.openbet.com:7999/dbs/environments.git/Config resource 'file [/tmp/config-repo-16512912790018624282/platform/pinnacle-def.yaml' via location 'platform/' (document#0)",
				activatesOnProfile);
		ConfigData configData = setupConfigServerConfigDataLoader(Arrays.asList(p6, p5, p4, p3, p2, p1),
				"application-slash", "def");
		assertThat(configData.getPropertySources()).hasSize(7);
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(2))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(2))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(3))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(3))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(4))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(4))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(5))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(5))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(6))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(6))
			.contains(ConfigData.Option.IGNORE_PROFILES)).isTrue();
	}

	@Test
	void testProfileSpecificPropertySourcesWithDefaultProfile() {
		PropertySource p1 = new PropertySource("overrides", Collections.singletonMap("foo", "bar"));
		PropertySource p2 = new PropertySource("classpath:/test-default/config-client/application.yaml",
				Collections.singletonMap("foo", "baroverride"));
		PropertySource p3 = new PropertySource(
				"git@github.com:demo/support-configuration-repo.git/Config resource 'file [/var/folders/k3/zv8hzdm17vv69j485fv3cf9r0000gp/T/config-repo-14772121892716396795/commons/application.properties' via location 'commons/' (document #0)",
				Collections.singletonMap("hello", "world"));
		PropertySource p4 = new PropertySource("aws:secrets:/secret/application-name_profile",
				Collections.singletonMap("hello", "world"));
		ConfigData configData = setupConfigServerConfigDataLoader(Arrays.asList(p4, p3, p2, p1), "application-slash",
				"default");
		assertThat(configData.getPropertySources()).hasSize(5);
		assertThat(configData.getOptions(configData.getPropertySources().get(0))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(1))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isTrue();
		assertThat(configData.getOptions(configData.getPropertySources().get(2))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(3))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
		assertThat(configData.getOptions(configData.getPropertySources().get(4))
			.contains(ConfigData.Option.PROFILE_SPECIFIC)).isFalse();
	}

	private ConfigData setupConfigServerConfigDataLoader(List<PropertySource> propertySources, String applicationName,
			String... profileList) {
		RestTemplate rest = mock(RestTemplate.class);
		Environment environment = new Environment("test", profileList);
		environment.addAll(propertySources);

		ResponseEntity<Environment> responseEntity = mock(ResponseEntity.class);
		when(responseEntity.getStatusCode()).thenReturn(HttpStatus.OK);
		when(responseEntity.getBody()).thenReturn(environment);
		when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Environment.class),
				eq(applicationName), ArgumentMatchers.<String>any()))
			.thenReturn(responseEntity);

		ConfigurableBootstrapContext bootstrapContext = mock(ConfigurableBootstrapContext.class);
		when(bootstrapContext.get(eq(ConfigClientRequestTemplateFactory.class)))
			.thenReturn(mock(ConfigClientRequestTemplateFactory.class));
		when(bootstrapContext.get(eq(RestTemplate.class))).thenReturn(rest);

		ConfigServerConfigDataLoader loader = new ConfigServerConfigDataLoader(destination -> mock(Log.class));
		ConfigDataLoaderContext context = mock(ConfigDataLoaderContext.class);
		when(context.getBootstrapContext()).thenReturn(bootstrapContext);

		ConfigClientProperties properties = new ConfigClientProperties();
		properties.setName(applicationName);
		Profiles profiles = mock(Profiles.class);
		when(profiles.getAccepted())
			.thenReturn(profileList == null ? Collections.singletonList("default") : Arrays.asList(profileList));
		ConfigServerConfigDataResource resource = new ConfigServerConfigDataResource(properties, false, profiles);
		resource.setProfileSpecific(!ObjectUtils.isEmpty(profileList));

		return loader.doLoad(context, resource);

	}

	private ConfigClientRequestTemplateFactory factory(ConfigClientProperties properties) {
		return new ConfigClientRequestTemplateFactory(LogFactory.getLog(getClass()), properties);
	}

	private void assertNextUriIsNotTried(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy,
			HttpStatus firstUriResponse, Class<? extends Exception> expectedCause) throws Exception {
		assertNextUriIsNotTried(true, multipleUriStrategy, firstUriResponse, expectedCause);
	}

	private void assertNextUriIsNotTried(boolean failFast,
			ConfigClientProperties.MultipleUriStrategy multipleUriStrategy, HttpStatus firstUriResponse,
			Class<? extends Exception> expectedCause) throws Exception {
		// Set up with two URIs.
		String badURI = "http://baduri";
		String goodURI = "http://localhost:8888";
		String[] uris = new String[] { badURI, goodURI };
		properties.setUri(uris);
		properties.setFailFast(failFast);
		// Strategy is CONNECTION_TIMEOUT_ONLY, so it should not try the next URI for
		// INTERNAL_SERVER_ERROR
		properties.setMultipleUriStrategy(multipleUriStrategy);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		mockRequestResponse(requestFactory, badURI, firstUriResponse);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);

		ThrowableAssertAlternative<ConfigClientFailFastException> throwableAssertAlternative = assertThatExceptionOfType(
				ConfigClientFailFastException.class)
			.isThrownBy(() -> this.loader.load(context, resource))
			.withMessageContaining("fail fast property is set");
		if (expectedCause != null) {
			throwableAssertAlternative.withCauseInstanceOf(expectedCause);
		}
	}

	@SuppressWarnings("SameParameterValue")
	private void assertNextUriIsNotTriedForClientError(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy,
			HttpStatus firstUriResponse) throws Exception {

		assertNextUriIsNotTried(multipleUriStrategy, firstUriResponse, HttpClientErrorException.class);
	}

	@SuppressWarnings("SameParameterValue")
	private void assertNextUriIsNotTriedForNotFoundError(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy,
			HttpStatus firstUriResponse) throws Exception {

		// NOT_FOUND is treated differently
		assertNextUriIsNotTried(multipleUriStrategy, firstUriResponse, null);
	}

	@SuppressWarnings("SameParameterValue")
	private void assertNextUriIsNotTriedForServerError(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy,
			HttpStatus firstUriResponse) throws Exception {

		assertNextUriIsNotTried(multipleUriStrategy, firstUriResponse, HttpServerErrorException.class);
	}

	@SuppressWarnings("SameParameterValue")
	private void assertNextUriIsTried(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy,
			HttpStatus firstUriResponse) throws Exception {

		// Set up with two URIs.
		String badURI = "http://baduri";
		String goodURI = "http://localhost:8888";
		String[] uris = new String[] { badURI, goodURI };
		properties.setUri(uris);
		properties.setFailFast(true);
		// Strategy is ALWAYS, so it should try all URIs until successful
		properties.setMultipleUriStrategy(multipleUriStrategy);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		mockRequestResponse(requestFactory, badURI, firstUriResponse);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);

		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	private void assertNextUriIsTriedOnTimeout(ConfigClientProperties.MultipleUriStrategy multipleUriStrategy)
			throws Exception {
		// Set up with two URIs.
		String badURI = "http://baduri";
		String goodURI = "http://localhost:8888";
		String[] uris = new String[] { badURI, goodURI };
		properties.setUri(uris);
		properties.setFailFast(true);
		// Strategy should not matter when the error is connection timed out
		properties.setMultipleUriStrategy(multipleUriStrategy);
		this.loader = new ConfigServerConfigDataLoader(destination -> logger);
		ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		when(bootstrapContext.get(RestTemplate.class)).thenReturn(restTemplate);

		// First URI times out. Second one is successful
		mockRequestTimedOut(requestFactory, badURI);
		mockRequestResponse(requestFactory, goodURI, HttpStatus.OK);
		assertThat(this.loader.load(context, resource)).isNotNull();
	}

	private void mockRequestResponse(ClientHttpRequestFactory requestFactory, String baseURI, HttpStatus status)
			throws Exception {
		ClientHttpRequest request = mock(ClientHttpRequest.class);
		ClientHttpResponse response = mock(ClientHttpResponse.class);

		if (baseURI == null) {
			when(requestFactory.createRequest(any(URI.class), any(HttpMethod.class))).thenReturn(request);
		}
		else {
			when(requestFactory.createRequest(eq(new URI(format(URI_TEMPLATE, baseURI, NAME, PROFILES, LABEL))),
					any(HttpMethod.class)))
				.thenReturn(request);
		}

		when(request.getHeaders()).thenReturn(new HttpHeaders());
		when(request.execute()).thenReturn(response);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		when(response.getHeaders()).thenReturn(headers);
		when(response.getStatusCode()).thenReturn(status);
		when(response.getBody()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
	}

	@SuppressWarnings("unchecked")
	private void mockRequestResponseWithLabel(ResponseEntity<?> response, String label) {
		when(this.restTemplate.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class),
				any(Class.class), anyString(), anyString(), eq(label)))
			.thenReturn(response);
	}

	@SuppressWarnings("unchecked")
	private void mockRequestResponseWithoutLabel(ResponseEntity<?> response) {
		when(this.restTemplate.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class),
				any(Class.class), anyString(), anyString()))
			.thenReturn(response);
	}

	@SuppressWarnings("unchecked")
	private void mockRequestTimedOut() {
		when(this.restTemplate.exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class),
				any(Class.class), anyString(), anyString(), anyString()))
			.thenThrow(ResourceAccessException.class);
	}

	private void mockRequestTimedOut(ClientHttpRequestFactory requestFactory, String baseURI) throws Exception {
		ClientHttpRequest request = mock(ClientHttpRequest.class);

		if (baseURI == null) {
			when(requestFactory.createRequest(any(URI.class), any(HttpMethod.class))).thenReturn(request);
		}
		else {
			when(requestFactory.createRequest(eq(new URI(format(URI_TEMPLATE, baseURI, NAME, PROFILES, LABEL))),
					any(HttpMethod.class)))
				.thenReturn(request);
		}

		when(request.getHeaders()).thenReturn(new HttpHeaders());
		when(request.execute()).thenThrow(IOException.class);
	}

}

/*
 * Copyright 2016-2019 the original author or authors.
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

package org.springframework.cloud.config.server.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.config.ConfigServerProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

/**
 * @author Clay McCoy
 * @author Matej Nedić
 */
@Testcontainers
@Tag("DockerRequired")
public class AwsS3EnvironmentRepositoryTests {

	@Container
	private static final LocalStackContainer localstack = new LocalStackContainer(
			DockerImageName.parse("localstack/localstack:4.0.3"))
		.withServices(S3);

	private final ConfigServerProperties server = new ConfigServerProperties();

	private final StaticCredentialsProvider staticCredentialsProvider = StaticCredentialsProvider
		.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));

	private final S3Client s3Client = S3Client.builder()
		.region(Region.of(localstack.getRegion()))
		.credentialsProvider(staticCredentialsProvider)
		.endpointOverride(localstack.getEndpointOverride(S3))
		.build();

	private final EnvironmentRepository envRepo = new AwsS3EnvironmentRepository(s3Client, "bucket1", false, server);

	private final EnvironmentRepository envRepoApplicationDir = new AwsS3EnvironmentRepository(s3Client, "bucket1",
			true, server);

	private final List<String> toBeRemoved = new ArrayList<>();

	final String yamlContent = "cloudfoundry:\n" + "  enabled: true\n" + "  accounts:\n" + "    - name: acc1\n"
			+ "      user: 'user1'\n" + "      password: 'password1'\n" + "      api: api.sys.acc1.cf-app.com\n"
			+ "      environment: test1\n" + "    - name: acc2\n" + "      user: 'user2'\n"
			+ "      password: 'password2'\n" + "      api: api.sys.acc2.cf-app.com\n" + "      environment: test2\n";

	final String jsonContent = "{\n" + " \"cloudfoundry\": {\n" + "  \"enabled\": true,\n" + "  \"accounts\": [{\n"
			+ "   \"name\": \"acc1\",\n" + "   \"user\": \"user1\",\n" + "   \"password\": \"password1\",\n"
			+ "   \"api\": \"api.sys.acc1.cf-app.com\",\n" + "   \"environment\": \"test1\"\n" + "  }, {\n"
			+ "   \"name\": \"acc2\",\n" + "   \"user\": \"user2\",\n" + "   \"password\": \"password2\",\n"
			+ "   \"api\": \"api.sys.acc2.cf-app.com\",\n" + "   \"environment\": \"test2\"\n" + "  }]\n" + " }\n"
			+ "}";

	final Properties expectedProperties = new Properties();

	{
		expectedProperties.put("cloudfoundry.enabled", true);
		expectedProperties.put("cloudfoundry.accounts[0].name", "acc1");
		expectedProperties.put("cloudfoundry.accounts[0].user", "user1");
		expectedProperties.put("cloudfoundry.accounts[0].password", "password1");
		expectedProperties.put("cloudfoundry.accounts[0].api", "api.sys.acc1.cf-app.com");
		expectedProperties.put("cloudfoundry.accounts[0].environment", "test1");
		expectedProperties.put("cloudfoundry.accounts[1].name", "acc2");
		expectedProperties.put("cloudfoundry.accounts[1].user", "user2");
		expectedProperties.put("cloudfoundry.accounts[1].password", "password2");
		expectedProperties.put("cloudfoundry.accounts[1].api", "api.sys.acc2.cf-app.com");
		expectedProperties.put("cloudfoundry.accounts[1].environment", "test2");
	}

	@BeforeAll
	public static void createBucket() {
		StaticCredentialsProvider staticCredentialsProvider = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
		S3Client s3Client = S3Client.builder()
			.region(Region.of(localstack.getRegion()))
			.credentialsProvider(staticCredentialsProvider)
			.endpointOverride(localstack.getEndpointOverride(S3))
			.build();
		s3Client.createBucket(CreateBucketRequest.builder().bucket("bucket1").build());
		s3Client.putBucketVersioning(PutBucketVersioningRequest.builder()
			.bucket("bucket1")
			.versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build())
			.build());
	}

	@AfterEach
	public void cleanUp() {
		toBeRemoved.forEach(
				value -> s3Client.deleteObject(DeleteObjectRequest.builder().bucket("bucket1").key(value).build()));
		toBeRemoved.clear();
	}

	@Test
	public void multiDocumentYaml() throws IOException {
		Resource resource = new ClassPathResource("awss3/foo.yaml");
		String yamlString = new String(Files.readAllBytes(Paths.get(resource.getURI())));
		putFiles("foo.yaml", yamlString);
		resource = new ClassPathResource("awss3/foo-test1.yaml");
		yamlString = new String(Files.readAllBytes(Paths.get(resource.getURI())));
		putFiles("foo-test1.yaml", yamlString);
		resource = new ClassPathResource("awss3/application-test1.yaml");
		yamlString = new String(Files.readAllBytes(Paths.get(resource.getURI())));
		putFiles("application-test1.yaml", yamlString);
		resource = new ClassPathResource("awss3/application.yaml");
		yamlString = new String(Files.readAllBytes(Paths.get(resource.getURI())));
		putFiles("application.yaml", yamlString);

		final Environment env = envRepo.findOne("foo", "test1", null);
		assertThat(env.getPropertySources().size()).isEqualTo(5);
		List<PropertySource> propertySources = env.getPropertySources();
		// @formatter:off
		assertThat(propertySources.get(0).getName()).isEqualTo("s3:foo-test1"); // foo-test1.yaml
		assertThat(propertySources.get(0).getSource().get("app")).isEqualTo("test-test1-yaml");
		assertThat(propertySources.get(1).getName()).isEqualTo("s3:application-test1"); // application-test1.yaml
		assertThat(propertySources.get(1).getSource().get("app")).isEqualTo("test1-yaml");
		assertThat(propertySources.get(2).getName()).isEqualTo("s3:foo-test1"); // profile specific document in foo.yaml
		assertThat(propertySources.get(2).getSource().get("a")).isEqualTo(1);
		assertThat(propertySources.get(2).getSource().get("spring.config.activate.onProfile")).isEqualTo("test1");
		assertThat(propertySources.get(3).getName()).isEqualTo("s3:foo"); // non-profile specific document in foo.yaml
		assertThat(propertySources.get(3).getSource().get("a")).isEqualTo(0);
		assertThat(propertySources.get(4).getName()).isEqualTo("s3:application"); // application.yaml
		assertThat(propertySources.get(4).getSource().get("app")).isEqualTo("yaml");
		// @formatter:on
	}

	@Test
	public void failToFindNonexistentObject() {
		Environment env = envRepo.findOne("foo", "bar", null);
		assertThat(env.getPropertySources()).isEmpty();
	}

	@Test
	public void findPropertiesObject() {
		String propertyContent = "cloudfoundry.enabled=true\n" + "cloudfoundry.accounts[0].name=acc1\n"
				+ "cloudfoundry.accounts[0].user=user1\n" + "cloudfoundry.accounts[0].password=password1\n"
				+ "cloudfoundry.accounts[0].api=api.sys.acc1.cf-app.com\n"
				+ "cloudfoundry.accounts[0].environment=test1\n" + "cloudfoundry.accounts[1].name=acc2\n"
				+ "cloudfoundry.accounts[1].user=user2\n" + "cloudfoundry.accounts[1].password=password2\n"
				+ "cloudfoundry.accounts[1].api=api.sys.acc2.cf-app.com\n"
				+ "cloudfoundry.accounts[1].environment=test2\n";
		String versionId = putFiles("foo-bar.properties", propertyContent);

		// Pulling content from a .properties file forces a boolean into a String
		expectedProperties.put("cloudfoundry.enabled", "true");

		final Environment env = envRepo.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findJsonObject() {
		String versionId = putFiles("foo-bar.json", jsonContent);

		final Environment env = envRepo.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findYamlObject() {
		String versionId = putFiles("foo-bar.yaml", yamlContent);

		final Environment env = envRepo.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findYmlObject() {
		String versionId = putFiles("foo-bar.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findWithDefaultProfile() {
		String versionId = putFiles("foo.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "default");
	}

	@Test
	public void findWithDefaultProfileUsingSuffix() {
		String versionId = putFiles("foo-default.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "default");
	}

	@Test
	public void findWithMultipleProfilesAllFound() {
		putFiles("foo-profile1.yml", yamlContent);
		String versionId = putFiles("foo-profile2.yml", jsonContent);

		final Environment env = envRepo.findOne("foo", "profile1,profile2", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 2, "profile1", "profile2");
	}

	@Test
	public void findWithMultipleProfilesOneFound() {
		String versionId = putFiles("foo-profile2.yml", jsonContent);

		final Environment env = envRepo.findOne("foo", "profile1,profile2", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "profile1", "profile2");
	}

	@Test
	public void findWithOneProfileDefaultOneFound() {
		putFiles("foo-profile1.yml", jsonContent);
		String versionId = putFiles("foo.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", "profile1", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 2, "profile1");
	}

	@Test
	public void findWithNoProfileAndNoServerDefaultOneFound() {
		server.setDefaultProfile(null);
		String versionId = putFiles("foo.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1);

	}

	@Test
	public void findWithLabel() {
		String versionId = putFiles("label1/foo-bar.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", "bar", "label1");

		assertExpectedEnvironment(env, "foo", "label1", versionId, 1, "bar");
	}

	@Test
	public void findWithVersion() {
		String versionId = putFiles("foo-bar.yml", yamlContent);

		final Environment env = envRepo.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findWithMultipleApplicationAllFound() {
		String versionId = putFiles("foo-profile1.yml", jsonContent);
		putFiles("bar-profile1.yml", jsonContent);

		final Environment env = envRepo.findOne("foo,bar", "profile1", null);
		assertThat(env.getPropertySources().get(0).getName()).isEqualTo("s3:bar-profile1");
		assertThat(env.getPropertySources().get(1).getName()).isEqualTo("s3:foo-profile1");

		assertExpectedEnvironment(env, "foo,bar", null, versionId, 2, "profile1");

	}

	@Test
	public void failToFindNonexistentObject_ApplicationDirVariant() {
		Environment env = envRepoApplicationDir.findOne("foo", "bar", null);
		assertThat(env.getPropertySources()).isEmpty();
	}

	@Test
	public void findPropertiesObject_ApplicationDirVariant() {
		String propertyContent = "cloudfoundry.enabled=true\n" + "cloudfoundry.accounts[0].name=acc1\n"
				+ "cloudfoundry.accounts[0].user=user1\n" + "cloudfoundry.accounts[0].password=password1\n"
				+ "cloudfoundry.accounts[0].api=api.sys.acc1.cf-app.com\n"
				+ "cloudfoundry.accounts[0].environment=test1\n" + "cloudfoundry.accounts[1].name=acc2\n"
				+ "cloudfoundry.accounts[1].user=user2\n" + "cloudfoundry.accounts[1].password=password2\n"
				+ "cloudfoundry.accounts[1].api=api.sys.acc2.cf-app.com\n"
				+ "cloudfoundry.accounts[1].environment=test2\n";
		String versionId = putFiles("foo/application-bar.properties", propertyContent);

		// Pulling content from a .properties file forces a boolean into a String
		expectedProperties.put("cloudfoundry.enabled", "true");

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findJsonObject_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-bar.json", jsonContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findYamlObject_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-bar.yaml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findYmlObject_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-bar.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findWithDefaultProfile_ApplicationDirVariant() {
		String versionId = putFiles("foo/application.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "default");
	}

	@Test
	public void findWithDefaultProfileUsingSuffix_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-default.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "default");
	}

	@Test
	public void findWithMultipleProfilesAllFound_ApplicationDirVariant() {
		putFiles("foo/application-profile1.yml", yamlContent);
		String versionId = putFiles("foo/application-profile2.yml", jsonContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "profile1,profile2", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 2, "profile1", "profile2");
	}

	@Test
	public void findWithMultipleProfilesOneFound_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-profile2.yml", jsonContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "profile1,profile2", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "profile1", "profile2");
	}

	@Test
	public void findWithOneProfileDefaultOneFound_ApplicationDirVariant() {
		putFiles("foo/application-profile1.yml", jsonContent);
		String versionId = putFiles("foo/application.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "profile1", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 2, "profile1");
	}

	@Test
	public void findWithNoProfileAndNoServerDefaultOneFound_ApplicationDirVariant() {
		server.setDefaultProfile(null);
		String versionId = putFiles("foo/application.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", null, null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1);

	}

	@Test
	public void findWithLabel_ApplicationDirVariant() {
		String versionId = putFiles("label1/foo/application-bar.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", "label1");

		assertExpectedEnvironment(env, "foo", "label1", versionId, 1, "bar");
	}

	@Test
	public void findWithVersion_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-bar.yml", yamlContent);

		final Environment env = envRepoApplicationDir.findOne("foo", "bar", null);

		assertExpectedEnvironment(env, "foo", null, versionId, 1, "bar");
	}

	@Test
	public void findWithMultipleApplicationAllFound_ApplicationDirVariant() {
		String versionId = putFiles("foo/application-profile1.yml", jsonContent);
		putFiles("bar/application-profile1.yml", jsonContent);

		final Environment env = envRepoApplicationDir.findOne("foo,bar", "profile1", null);
		assertThat(env.getPropertySources().get(0).getName()).isEqualTo("s3:bar/application-profile1");
		assertThat(env.getPropertySources().get(1).getName()).isEqualTo("s3:foo/application-profile1");

		assertExpectedEnvironment(env, "foo,bar", null, versionId, 2, "profile1");

	}

	@Test
	public void factoryCustomizable() {
		AwsS3EnvironmentRepositoryFactory factory = new AwsS3EnvironmentRepositoryFactory(new ConfigServerProperties());
		AwsS3EnvironmentProperties properties = new AwsS3EnvironmentProperties();
		properties.setRegion("us-east-1");
		properties.setEndpoint("https://myawsendpoint/");
		AwsS3EnvironmentRepository repository = factory.build(properties);
		assertThat(repository).isNotNull();
	}

	@Test
	public void getLocationsTest() {
		AwsS3EnvironmentRepositoryFactory factory = new AwsS3EnvironmentRepositoryFactory(new ConfigServerProperties());
		AwsS3EnvironmentProperties properties = new AwsS3EnvironmentProperties();
		properties.setRegion("us-east-1");
		properties.setBucket("test");
		AwsS3EnvironmentRepository repository = factory.build(properties);

		assertThat(repository.getLocations("app", "default", "main")).isEqualTo(
				new SearchPathLocator.Locations("app", "default", "main", null, new String[] { "s3://test/main" }));

		assertThat(repository.getLocations("app", "default", null))
			.isEqualTo(new SearchPathLocator.Locations("app", "default", null, null, new String[] { "s3://test/" }));

		assertThat(repository.getLocations("app", "default", ""))
			.isEqualTo(new SearchPathLocator.Locations("app", "default", "", null, new String[] { "s3://test/" }));

		ConfigServerProperties configServerProperties = new ConfigServerProperties();
		configServerProperties.setDefaultLabel("defaultlabel");
		factory = new AwsS3EnvironmentRepositoryFactory(configServerProperties);
		repository = factory.build(properties);

		assertThat(repository.getLocations("app", "default", null)).isEqualTo(new SearchPathLocator.Locations("app",
				"default", "defaultlabel", null, new String[] { "s3://test/defaultlabel" }));

		assertThat(repository.getLocations("app", "default", "")).isEqualTo(new SearchPathLocator.Locations("app",
				"default", "defaultlabel", null, new String[] { "s3://test/defaultlabel" }));
	}

	private String putFiles(String fileName, String propertyContent) {
		toBeRemoved.add(fileName);
		return s3Client
			.putObject(PutObjectRequest.builder().bucket("bucket1").key(fileName).build(),
					RequestBody.fromString((propertyContent)))
			.versionId();
	}

	private void assertExpectedEnvironment(Environment env, String applicationName, String label, String versionId,
			int propertySourceCount, String... profiles) {
		assertThat(env.getName()).isEqualTo(applicationName);
		assertThat(env.getProfiles()).isEqualTo(profiles);
		assertThat(env.getLabel()).isEqualTo(label);
		assertThat(env.getVersion()).isEqualTo(versionId);
		assertThat(env.getPropertySources()).hasSize(propertySourceCount);
		for (PropertySource ps : env.getPropertySources()) {
			assertThat(ps.getSource()).isEqualTo(expectedProperties);
		}
	}

}

// Copyright 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import static oracle.kubernetes.LogMatcher.containsInfo;
import static oracle.kubernetes.operator.LabelConstants.RESOURCE_VERSION_LABEL;
import static oracle.kubernetes.operator.logging.MessageKeys.ADMIN_POD_CREATED;
import static oracle.kubernetes.operator.logging.MessageKeys.ADMIN_POD_EXISTS;
import static oracle.kubernetes.operator.logging.MessageKeys.ADMIN_POD_REPLACED;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Container;
import io.kubernetes.client.models.V1ContainerPort;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodSpec;
import io.kubernetes.client.models.V1Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import oracle.kubernetes.operator.PodAwaiterStepFactory;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.work.AsyncCallTestSupport;
import oracle.kubernetes.operator.work.FiberTestSupport;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.v1.ServerStartup;
import org.junit.Test;

@SuppressWarnings({"ConstantConditions, unchecked", "SameParameterValue", "deprecation"})
public class AdminPodHelperTest extends PodHelperTestBase {
  private static final String INTERNAL_OPERATOR_CERT_FILE_PARAM = "internalOperatorCert";
  private static final String INTERNAL_OPERATOR_CERT_ENV_NAME = "INTERNAL_OPERATOR_CERT";
  private static final String CERTFILE = "certfile";

  public AdminPodHelperTest() {
    super(ADMIN_SERVER, ADMIN_PORT);
  }

  @Override
  String getPodCreatedMessageKey() {
    return ADMIN_POD_CREATED;
  }

  @Override
  FiberTestSupport.StepFactory getStepFactory() {
    return PodHelper::createAdminPodStep;
  }

  @Override
  void expectStepsAfterCreation() {}

  @Override
  String getPodExistsMessageKey() {
    return ADMIN_POD_EXISTS;
  }

  @Override
  String getPodReplacedMessageKey() {
    return ADMIN_POD_REPLACED;
  }

  private void verifyAdminPodReplacedWhen(PodMutator mutator) {
    testSupport.addComponent(
        ProcessingConstants.PODWATCHER_COMPONENT_NAME,
        PodAwaiterStepFactory.class,
        (pod, next) -> terminalStep);

    V1Pod existingPod = createPodModel();
    mutator.mutate(existingPod);
    initializeExistingPod(existingPod);
    expectDeletePod(getPodName()).returning(new V1Status());
    expectCreatePod(podWithName(getPodName())).returning(createPodModel());

    testSupport.runSteps(getStepFactory(), terminalStep);

    assertThat(logRecords, containsInfo(getPodReplacedMessageKey()));
  }

  private AsyncCallTestSupport.CannedResponse<V1Status> expectDeletePod(String podName) {
    return testSupport
        .createCannedResponse("deletePod")
        .withNamespace(NS)
        .ignoringBody()
        .withName(podName);
  }

  @Test
  public void whenDeleteReportsNotFound_replaceAdminPod() {
    testSupport.addComponent(
        ProcessingConstants.PODWATCHER_COMPONENT_NAME,
        PodAwaiterStepFactory.class,
        (pod, next) -> terminalStep);

    initializeExistingPod(getIncompatiblePod());
    expectDeletePod(getPodName()).failingWithStatus(CallBuilder.NOT_FOUND);
    expectCreatePod(podWithName(getPodName())).returning(createPodModel());

    testSupport.runSteps(getStepFactory(), terminalStep);

    assertThat(logRecords, containsInfo(getPodReplacedMessageKey()));
  }

  private V1Pod getIncompatiblePod() {
    V1Pod existingPod = createPodModel();
    existingPod.getSpec().setContainers(null);
    return existingPod;
  }

  @Test
  public void whenAdminPodDeletionFails_retryOnFailure() {
    testSupport.addRetryStrategy(retryStrategy);
    initializeExistingPod(getIncompatiblePod());
    expectDeletePod(getPodName()).failingWithStatus(401);
    expectStepsAfterCreation();

    FiberTestSupport.StepFactory stepFactory = getStepFactory();
    Step initialStep = stepFactory.createStepList(terminalStep);
    testSupport.runSteps(initialStep);

    testSupport.verifyCompletionThrowable(ApiException.class);
  }

  @Test
  public void whenAdminPodReplacementFails_retryOnFailure() {
    testSupport.addRetryStrategy(retryStrategy);
    initializeExistingPod(getIncompatiblePod());
    expectDeletePod(getPodName()).returning(new V1Status());
    expectCreatePod(podWithName(getPodName())).failingWithStatus(401);
    expectStepsAfterCreation();

    FiberTestSupport.StepFactory stepFactory = getStepFactory();
    Step initialStep = stepFactory.createStepList(terminalStep);
    testSupport.runSteps(initialStep);

    testSupport.verifyCompletionThrowable(ApiException.class);
  }

  @Test
  public void whenExistingAdminPodHasBadVersion_replaceIt() {
    verifyAdminPodReplacedWhen(
        pod -> pod.getMetadata().putLabelsItem(RESOURCE_VERSION_LABEL, "??"));
  }

  @Test
  public void whenExistingAdminPodSpecHasNoContainers_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> pod.getSpec().setContainers(null));
  }

  @Test
  public void whenExistingAdminPodSpecHasNoContainersWithExpectedName_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).setName("???"));
  }

  private V1Container getSpecContainer(V1Pod pod) {
    return pod.getSpec().getContainers().get(0);
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasWrongImage_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).setImage(VERSIONED_IMAGE));
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasWrongImagePullPolicy_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).setImagePullPolicy("NONE"));
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasNoPorts_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).setPorts(Collections.emptyList()));
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasExtraPort_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).addPortsItem(definePort(1234)));
  }

  private V1ContainerPort definePort(int port) {
    return new V1ContainerPort().protocol("TCP").containerPort(port);
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasIncorrectPort_replaceIt() {
    verifyAdminPodReplacedWhen(
        (pod) -> getSpecContainer(pod).getPorts().get(0).setContainerPort(1234));
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasWrongEnvVariable_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).getEnv().get(0).setValue("???"));
  }

  @Test
  public void whenExistingAdminPodSpecContainerHasWrongEnvFrom_replaceIt() {
    verifyAdminPodReplacedWhen((pod) -> getSpecContainer(pod).envFrom(Collections.emptyList()));
  }

  @Test
  public void whenAdminPodCreated_specHasPodNameAsHostName() {
    assertThat(getCreatedPod().getSpec().getHostname(), equalTo(getPodName()));
  }

  @Test
  public void whenAdminPodCreated_containerHasStartServerCommand() {
    assertThat(
        getCreatedPodSpecContainer().getCommand(),
        contains("/weblogic-operator/scripts/startServer.sh", UID, getServerName(), DOMAIN_NAME));
  }

  @Test
  public void whenAdminPodCreated_hasOperatorCertEnvVariable() {
    putTuningParameter(INTERNAL_OPERATOR_CERT_FILE_PARAM, CERTFILE);
    assertThat(
        getCreatedPodSpecContainer().getEnv(),
        hasEnvVar(INTERNAL_OPERATOR_CERT_ENV_NAME, CERTFILE));
  }

  @Test
  public void whenDomainPresenceHasNullEnvironmentItems_createAdminPodStartupWithDefaultItems() {
    domainPresenceInfo.getDomain().getSpec().setServerStartup(null);

    assertThat(getCreatedPodSpecContainer().getEnv(), not(empty()));
  }

  @Test
  public void whenDomainPresenceHasEnvironmentItems_createAdminPodStartupWithThem() {
    domainPresenceInfo
        .getDomain()
        .getSpec()
        .setServerStartup(
            new ServerStartupListBuilder(ADMIN_SERVER)
                .withVar("item1", "value1")
                .withVar("item2", "value2")
                .build());

    assertThat(
        getCreatedPodSpecContainer().getEnv(),
        allOf(hasEnvVar("item1", "value1"), hasEnvVar("item2", "value2")));
  }

  @Test
  public void whenDomainPresenceHasEnvironmentItemsWithVariables_createAdminPodStartupWithThem() {
    domainPresenceInfo
        .getDomain()
        .getSpec()
        .setServerStartup(
            new ServerStartupListBuilder(ADMIN_SERVER)
                .withVar("item1", "find $(DOMAIN_NAME) at $(DOMAIN_HOME)")
                .withVar("item2", "$(SERVER_NAME) is $(ADMIN_NAME):$(ADMIN_PORT)")
                .build());

    assertThat(
        getCreatedPodSpecContainer().getEnv(),
        allOf(
            hasEnvVar("item1", "find domain1 at /shared/domain/domain1"),
            hasEnvVar("item2", "ADMIN_SERVER is ADMIN_SERVER:7001")));
  }

  static class ServerStartupListBuilder {
    private String serverName;
    private List<V1EnvVar> vars = new ArrayList<>();

    ServerStartupListBuilder(String serverName) {
      this.serverName = serverName;
    }

    ServerStartupListBuilder withVar(String name, String value) {
      vars.add(new V1EnvVar().name(name).value(value));
      return this;
    }

    List<ServerStartup> build() {
      return Collections.singletonList(
          new ServerStartup().withServerName(serverName).withEnv(vars));
    }
  }

  @Override
  protected void onAdminExpectListPersistentVolume() {
    expectListPersistentVolume().returning(createPersistentVolumeList());
  }

  @Override
  V1Pod createPodModel() {
    return new V1Pod().metadata(createPodMetadata()).spec(createPodSpec());
  }

  @Override
  V1PodSpec createPodSpec() {
    return super.createPodSpec().hostname("localhost");
  }

  @Override
  V1Container createPodSpecContainer() {
    return super.createPodSpecContainer()
        .addEnvItem(envItem(INTERNAL_OPERATOR_CERT_ENV_NAME, null));
  }

  @Override
  List<String> createStartCommand() {
    return Arrays.asList(
        "/weblogic-operator/scripts/startServer.sh", UID, getServerName(), DOMAIN_NAME);
  }
}

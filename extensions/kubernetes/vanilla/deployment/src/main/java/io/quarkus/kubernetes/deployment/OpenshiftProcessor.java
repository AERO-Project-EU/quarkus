
package io.quarkus.kubernetes.deployment;

import static io.quarkus.kubernetes.deployment.Constants.DEFAULT_HTTP_PORT;
import static io.quarkus.kubernetes.deployment.Constants.DEFAULT_S2I_IMAGE_NAME;
import static io.quarkus.kubernetes.deployment.Constants.HTTP_PORT;
import static io.quarkus.kubernetes.deployment.Constants.OPENSHIFT;
import static io.quarkus.kubernetes.deployment.Constants.OPENSHIFT_APP_RUNTIME;
import static io.quarkus.kubernetes.deployment.Constants.QUARKUS;
import static io.quarkus.kubernetes.deployment.Constants.ROUTE;
import static io.quarkus.kubernetes.deployment.OpenshiftConfig.OpenshiftFlavor.v3;
import static io.quarkus.kubernetes.spi.KubernetesDeploymentTargetBuildItem.DEFAULT_PRIORITY;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.dekorate.kubernetes.annotation.ServiceType;
import io.dekorate.kubernetes.config.EnvBuilder;
import io.dekorate.kubernetes.config.ImageConfiguration;
import io.dekorate.kubernetes.config.ImageConfigurationBuilder;
import io.dekorate.kubernetes.decorator.AddAnnotationDecorator;
import io.dekorate.kubernetes.decorator.AddEnvVarDecorator;
import io.dekorate.kubernetes.decorator.AddLabelDecorator;
import io.dekorate.kubernetes.decorator.ApplicationContainerDecorator;
import io.dekorate.kubernetes.decorator.ApplyImagePullPolicyDecorator;
import io.dekorate.openshift.decorator.ApplyReplicasToDeploymentConfigDecorator;
import io.dekorate.project.Project;
import io.dekorate.s2i.config.S2iBuildConfig;
import io.dekorate.s2i.config.S2iBuildConfigBuilder;
import io.dekorate.s2i.decorator.AddBuilderImageStreamResourceDecorator;
import io.dekorate.s2i.decorator.AddDockerImageStreamResourceDecorator;
import io.quarkus.container.image.deployment.ContainerImageConfig;
import io.quarkus.container.image.deployment.util.ImageUtil;
import io.quarkus.container.spi.BaseImageInfoBuildItem;
import io.quarkus.container.spi.ContainerImageInfoBuildItem;
import io.quarkus.container.spi.ContainerImageLabelBuildItem;
import io.quarkus.container.spi.FallbackContainerImageRegistryBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.kubernetes.deployment.OpenshiftConfig.DeploymentResourceKind;
import io.quarkus.kubernetes.spi.ConfiguratorBuildItem;
import io.quarkus.kubernetes.spi.CustomProjectRootBuildItem;
import io.quarkus.kubernetes.spi.DecoratorBuildItem;
import io.quarkus.kubernetes.spi.KubernetesAnnotationBuildItem;
import io.quarkus.kubernetes.spi.KubernetesCommandBuildItem;
import io.quarkus.kubernetes.spi.KubernetesDeploymentTargetBuildItem;
import io.quarkus.kubernetes.spi.KubernetesEnvBuildItem;
import io.quarkus.kubernetes.spi.KubernetesHealthLivenessPathBuildItem;
import io.quarkus.kubernetes.spi.KubernetesHealthReadinessPathBuildItem;
import io.quarkus.kubernetes.spi.KubernetesLabelBuildItem;
import io.quarkus.kubernetes.spi.KubernetesPortBuildItem;
import io.quarkus.kubernetes.spi.KubernetesResourceMetadataBuildItem;
import io.quarkus.kubernetes.spi.KubernetesRoleBindingBuildItem;
import io.quarkus.kubernetes.spi.KubernetesRoleBuildItem;

public class OpenshiftProcessor {

    private static final int OPENSHIFT_PRIORITY = DEFAULT_PRIORITY;
    private static final String OPENSHIFT_INTERNAL_REGISTRY = "image-registry.openshift-image-registry.svc:5000";
    private static final String DOCKERIO_REGISTRY = "docker.io";
    private static final String OPENSHIFT_V3_APP = "app";

    @BuildStep
    public void checkOpenshift(ApplicationInfoBuildItem applicationInfo, Capabilities capabilities, OpenshiftConfig config,
            BuildProducer<KubernetesDeploymentTargetBuildItem> deploymentTargets,
            BuildProducer<KubernetesResourceMetadataBuildItem> resourceMeta) {
        List<String> targets = KubernetesConfigUtil.getUserSpecifiedDeploymentTargets();
        boolean openshiftEnabled = targets.contains(OPENSHIFT);

        DeploymentResourceKind deploymentResourceKind = config.getDeploymentResourceKind(capabilities);
        deploymentTargets.produce(
                new KubernetesDeploymentTargetBuildItem(OPENSHIFT, deploymentResourceKind.kind, deploymentResourceKind.apiGroup,
                        deploymentResourceKind.apiVersion, OPENSHIFT_PRIORITY, openshiftEnabled));
        if (openshiftEnabled) {
            String name = ResourceNameUtil.getResourceName(config, applicationInfo);
            resourceMeta.produce(new KubernetesResourceMetadataBuildItem(OPENSHIFT, deploymentResourceKind.apiGroup,
                    deploymentResourceKind.apiVersion, deploymentResourceKind.kind, name));
        }
    }

    @BuildStep
    public void populateInternalRegistry(OpenshiftConfig openshiftConfig, ContainerImageConfig containerImageConfig,
            Capabilities capabilities,
            BuildProducer<FallbackContainerImageRegistryBuildItem> containerImageRegistry) {

        if (!containerImageConfig.registry.isPresent()) {
            DeploymentResourceKind deploymentResourceKind = openshiftConfig.getDeploymentResourceKind(capabilities);
            if (deploymentResourceKind != DeploymentResourceKind.DeploymentConfig) {
                if (openshiftConfig.isOpenshiftBuildEnabled(containerImageConfig, capabilities)) {
                    // Images stored in internal openshift registry use the following pattern:
                    // 'image-registry.openshift-image-registry.svc:5000/{{ project name}}/{{ image name }}: {{image version }}.
                    // So, we need warn users if group does not match currently selected project.
                    containerImageRegistry.produce(new FallbackContainerImageRegistryBuildItem(OPENSHIFT_INTERNAL_REGISTRY));
                } else {
                    containerImageRegistry.produce(new FallbackContainerImageRegistryBuildItem(DOCKERIO_REGISTRY));
                }
            }
        }
    }

    @BuildStep
    public void createAnnotations(OpenshiftConfig config, BuildProducer<KubernetesAnnotationBuildItem> annotations) {
        config.getAnnotations().forEach((k, v) -> {
            annotations.produce(new KubernetesAnnotationBuildItem(k, v, OPENSHIFT));
        });
    }

    @BuildStep
    public void createLabels(OpenshiftConfig config, BuildProducer<KubernetesLabelBuildItem> labels,
            BuildProducer<ContainerImageLabelBuildItem> imageLabels) {
        config.getLabels().forEach((k, v) -> {
            labels.produce(new KubernetesLabelBuildItem(k, v, OPENSHIFT));
            imageLabels.produce(new ContainerImageLabelBuildItem(k, v));
        });
    }

    @BuildStep
    public List<ConfiguratorBuildItem> createConfigurators(ApplicationInfoBuildItem applicationInfo,
            OpenshiftConfig config, Capabilities capabilities, Optional<ContainerImageInfoBuildItem> image,
            List<KubernetesPortBuildItem> ports) {

        List<ConfiguratorBuildItem> result = new ArrayList<>();

        KubernetesCommonHelper.combinePorts(ports, config).values().forEach(value -> {
            result.add(new ConfiguratorBuildItem(new AddPortToOpenshiftConfig(value)));
        });
        result.add(new ConfiguratorBuildItem(new ApplyOpenshiftRouteConfigurator(config.route)));

        // Handle remote debug configuration for container ports
        if (config.remoteDebug.enabled) {
            result.add(new ConfiguratorBuildItem(new AddPortToOpenshiftConfig(config.remoteDebug.buildDebugPort())));
        }

        if (!capabilities.isPresent(Capability.CONTAINER_IMAGE_S2I)
                && !capabilities.isPresent("io.quarkus.openshift")
                && !capabilities.isPresent(Capability.CONTAINER_IMAGE_OPENSHIFT)) {
            result.add(new ConfiguratorBuildItem(new DisableS2iConfigurator()));

            image.flatMap(ContainerImageInfoBuildItem::getRegistry).ifPresent(r -> {
                result.add(new ConfiguratorBuildItem(new ApplyImageRegistryConfigurator(r)));
            });

            image.map(ContainerImageInfoBuildItem::getGroup).ifPresent(g -> {
                result.add(new ConfiguratorBuildItem(new ApplyImageGroupConfigurator(g)));
            });

        }
        return result;
    }

    @BuildStep
    public List<DecoratorBuildItem> createDecorators(ApplicationInfoBuildItem applicationInfo,
            OutputTargetBuildItem outputTarget,
            OpenshiftConfig config,
            ContainerImageConfig containerImageConfig,
            Optional<FallbackContainerImageRegistryBuildItem> fallbackRegistry,
            PackageConfig packageConfig,
            Optional<MetricsCapabilityBuildItem> metricsConfiguration,
            Capabilities capabilities,
            List<KubernetesAnnotationBuildItem> annotations,
            List<KubernetesLabelBuildItem> labels,
            List<KubernetesEnvBuildItem> envs,
            Optional<BaseImageInfoBuildItem> baseImage,
            Optional<ContainerImageInfoBuildItem> image,
            Optional<KubernetesCommandBuildItem> command,
            List<KubernetesPortBuildItem> ports,
            Optional<KubernetesHealthLivenessPathBuildItem> livenessPath,
            Optional<KubernetesHealthReadinessPathBuildItem> readinessPath,
            List<KubernetesRoleBuildItem> roles,
            List<KubernetesRoleBindingBuildItem> roleBindings,
            Optional<CustomProjectRootBuildItem> customProjectRoot,
            List<KubernetesDeploymentTargetBuildItem> targets) {

        List<DecoratorBuildItem> result = new ArrayList<>();
        if (!targets.stream().filter(KubernetesDeploymentTargetBuildItem::isEnabled)
                .anyMatch(t -> OPENSHIFT.equals(t.getName()))) {
            return result;
        }

        String name = ResourceNameUtil.getResourceName(config, applicationInfo);

        Optional<Project> project = KubernetesCommonHelper.createProject(applicationInfo, customProjectRoot, outputTarget,
                packageConfig);
        result.addAll(KubernetesCommonHelper.createDecorators(project, OPENSHIFT, name, config,
                metricsConfiguration,
                annotations, labels, command,
                ports, livenessPath, readinessPath, roles, roleBindings));

        if (config.flavor == v3) {
            //Openshift 3.x doesn't recognize 'app.kubernetes.io/name', it uses 'app' instead.
            //The decorator will be applied even on non-openshift resources is it may affect for example: knative
            if (labels.stream().filter(l -> OPENSHIFT.equals(l.getTarget()))
                    .noneMatch(l -> l.getKey().equals(OPENSHIFT_V3_APP))) {
                result.add(new DecoratorBuildItem(new AddLabelDecorator(name, OPENSHIFT_V3_APP, name)));
            }

            // The presence of optional is causing issues in OCP 3.11, so we better remove them.
            // The following 4 decorator will set the optional property to null, so that it won't make it into the file.
            //The decorators will be applied even on non-openshift resources is they may affect for example: knative
            result.add(new DecoratorBuildItem(new RemoveOptionalFromSecretEnvSourceDecorator()));
            result.add(new DecoratorBuildItem(new RemoveOptionalFromConfigMapEnvSourceDecorator()));
            result.add(new DecoratorBuildItem(new RemoveOptionalFromSecretKeySelectorDecorator()));
            result.add(new DecoratorBuildItem(new RemoveOptionalFromConfigMapKeySelectorDecorator()));
        }

        DeploymentResourceKind deploymentKind = config.getDeploymentResourceKind(capabilities);
        switch (deploymentKind) {
            case Deployment:
                result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveDeploymentConfigResourceDecorator(name)));
                result.add(new DecoratorBuildItem(OPENSHIFT, new AddDeploymentResourceDecorator(name, config)));
                break;
            case StatefulSet:
                result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveDeploymentConfigResourceDecorator(name)));
                result.add(new DecoratorBuildItem(OPENSHIFT, new AddStatefulSetResourceDecorator(name, config)));
                break;
            case Job:
                result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveDeploymentConfigResourceDecorator(name)));
                result.add(new DecoratorBuildItem(OPENSHIFT, new AddJobResourceDecorator(name, config.job)));
                break;
            case CronJob:
                result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveDeploymentConfigResourceDecorator(name)));
                result.add(new DecoratorBuildItem(OPENSHIFT, new AddCronJobResourceDecorator(name, config.cronJob)));
                break;
        }

        if (config.route != null) {
            for (Map.Entry<String, String> annotation : config.route.annotations.entrySet()) {
                result.add(new DecoratorBuildItem(OPENSHIFT,
                        new AddAnnotationDecorator(name, annotation.getKey(), annotation.getValue(), ROUTE)));
            }
        }

        if (config.getReplicas() != 1) {
            // This only affects DeploymentConfig
            result.add(new DecoratorBuildItem(OPENSHIFT,
                    new ApplyReplicasToDeploymentConfigDecorator(name, config.getReplicas())));
            // This only affects Deployment
            result.add(new DecoratorBuildItem(OPENSHIFT,
                    new io.dekorate.kubernetes.decorator.ApplyReplicasToDeploymentDecorator(name, config.getReplicas())));
            // This only affects StatefulSet
            result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyReplicasToStatefulSetDecorator(name, config.getReplicas())));
        }

        image.ifPresent(i -> {
            result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyContainerImageDecorator(name, i.getImage())));
        });
        if ((!capabilities.isPresent(Capability.CONTAINER_IMAGE_S2I) && !capabilities.isPresent(Capability.OPENSHIFT))
                || (capabilities.isPresent(Capability.OPENSHIFT) && !(capabilities.isPresent(Capability.CONTAINER_IMAGE_S2I)
                        || capabilities.isPresent(Capability.CONTAINER_IMAGE_OPENSHIFT)))) {
            result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveDeploymentTriggerDecorator()));
        }

        config.getContainerName().ifPresent(containerName -> {
            result.add(new DecoratorBuildItem(OPENSHIFT, new ChangeContainerNameDecorator(containerName)));
            result.add(new DecoratorBuildItem(OPENSHIFT, new ChangeContainerNameInDeploymentTriggerDecorator(containerName)));
        });

        result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyImagePullPolicyDecorator(name, config.getImagePullPolicy())));

        if (labels.stream().filter(l -> OPENSHIFT.equals(l.getTarget()))
                .noneMatch(l -> l.getKey().equals(OPENSHIFT_APP_RUNTIME))) {
            result.add(new DecoratorBuildItem(OPENSHIFT, new AddLabelDecorator(name, OPENSHIFT_APP_RUNTIME, QUARKUS)));
        }

        Stream.concat(config.convertToBuildItems().stream(),
                envs.stream().filter(e -> e.getTarget() == null || OPENSHIFT.equals(e.getTarget()))).forEach(e -> {
                    result.add(new DecoratorBuildItem(OPENSHIFT,
                            new AddEnvVarDecorator(ApplicationContainerDecorator.ANY, name,
                                    new EnvBuilder().withName(EnvConverter.convertName(e.getName())).withValue(e.getValue())
                                            .withSecret(e.getSecret()).withConfigmap(e.getConfigMap()).withField(e.getField())
                                            .build())));
                });

        // Handle custom s2i builder images
        baseImage.map(BaseImageInfoBuildItem::getImage).ifPresent(builderImage -> {
            String builderImageName = ImageUtil.getName(builderImage);
            S2iBuildConfig s2iBuildConfig = new S2iBuildConfigBuilder().withBuilderImage(builderImage).build();
            if (!DEFAULT_S2I_IMAGE_NAME.equals(builderImageName)) {
                result.add(new DecoratorBuildItem(OPENSHIFT, new RemoveBuilderImageResourceDecorator(DEFAULT_S2I_IMAGE_NAME)));
            }

            if (containerImageConfig.builder.isEmpty() || config.isOpenshiftBuildEnabled(containerImageConfig, capabilities)) {
                result.add(new DecoratorBuildItem(OPENSHIFT, new AddBuilderImageStreamResourceDecorator(s2iBuildConfig)));
                result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyBuilderImageDecorator(name, builderImage)));
            }
        });

        // Service handling
        result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyServiceTypeDecorator(name, config.getServiceType().name())));
        if ((config.getServiceType() == ServiceType.NodePort) && config.nodePort.isPresent()) {
            result.add(new DecoratorBuildItem(OPENSHIFT, new AddNodePortDecorator(name, config.nodePort.getAsInt())));
        }

        // Probe port handling
        Integer port = ports.stream().filter(p -> HTTP_PORT.equals(p.getName())).map(KubernetesPortBuildItem::getPort)
                .findFirst().orElse(DEFAULT_HTTP_PORT);
        result.add(new DecoratorBuildItem(OPENSHIFT, new ApplyHttpGetActionPortDecorator(name, name, port)));

        // Handle non-openshift builds
        if (deploymentKind == DeploymentResourceKind.DeploymentConfig
                && !OpenshiftConfig.isOpenshiftBuildEnabled(containerImageConfig, capabilities)) {
            image.ifPresent(i -> {
                String registry = containerImageConfig.registry
                        .orElse(fallbackRegistry.map(f -> f.getRegistry()).orElse("docker.io"));
                String repositoryWithRegistry = registry + "/" + i.getRepository();
                ImageConfiguration imageConfiguration = new ImageConfigurationBuilder()
                        .withName(name)
                        .withRegistry(registry)
                        .build();

                result.add(new DecoratorBuildItem(OPENSHIFT,
                        new AddDockerImageStreamResourceDecorator(imageConfiguration, repositoryWithRegistry)));
            });
        }

        // Handle remote debug configuration
        if (config.remoteDebug.enabled) {
            result.add(new DecoratorBuildItem(OPENSHIFT, new AddEnvVarDecorator(ApplicationContainerDecorator.ANY, name,
                    config.remoteDebug.buildJavaToolOptionsEnv())));
        }

        return result;
    }
}

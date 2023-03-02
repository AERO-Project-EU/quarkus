package io.quarkus.cli.deploy;

import io.quarkus.cli.BuildToolContext;
import picocli.CommandLine;

@CommandLine.Command(name = "openshift", sortOptions = false, showDefaultValues = true, mixinStandardHelpOptions = false, header = "Perform the deploy action on openshift.", description = "%n"
        + "The command will deploy the application on openshift.", footer = "%n"
                + "For example (using default values), it will create a Deployment named '<project.artifactId>' using the image with REPOSITORY='${user.name}/<project.artifactId>' and TAG='<project.version>' and will deploy it to the target cluster.", headerHeading = "%n", commandListHeading = "%nCommands:%n", synopsisHeading = "%nUsage: ", parameterListHeading = "%n", optionListHeading = "Options:%n")
public class Openshift extends BaseKubernetesDeployCommand {

    private static final String OPENSHIFT = "openshift";
    private static final String OPENSHIFT_EXTENSION = "io.quarkus:quarkus-openshift";
    private static final String DEPLOYMENT_KIND = "quarkus.openshift.deployment-kind";

    public enum DeploymentKind {
        Deployment,
        DeploymentConfig,
        StatefulSet,
        Job
    }

    @CommandLine.Option(names = { "--deployment-kind" }, description = "The kind of resource to generate and deploy")
    public DeploymentKind kind = DeploymentKind.DeploymentConfig;

    @Override
    public void populateContext(BuildToolContext context) {
        super.populateContext(context);
        context.getPropertiesOptions().properties.put(String.format(QUARKUS_DEPLOY_FORMAT, OPENSHIFT), "true");
        context.getPropertiesOptions().properties.put(DEPLOYMENT_KIND, kind.name());
        context.getForcedExtensions().add(OPENSHIFT_EXTENSION);
    }

    @Override
    public String getDefaultImageBuilder() {
        return OPENSHIFT;
    }
}
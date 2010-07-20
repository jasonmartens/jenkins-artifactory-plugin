package org.jfrog.hudson.ivy;

import com.google.common.collect.Maps;
import hudson.Extension;
import hudson.Launcher;
import hudson.ivy.AntIvyBuildWrapper;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.remoting.Which;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.Scrambler;
import net.sf.json.JSONObject;
import org.aspectj.weaver.loadtime.Agent;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.build.config.ArtifactoryIvySettingsConfigurator;
import org.jfrog.hudson.ArtifactoryBuilder;
import org.jfrog.hudson.ArtifactoryRedeployPublisher;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.BuildInfoResultAction;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.util.ActionableHelper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;


/**
 * @author Tomer Cohen
 */
public class ArtifactoryIvyConfigurator extends AntIvyBuildWrapper {

    private ServerDetails details;
    private String username;
    private String password;
    private boolean deployArtifacts;
    private boolean deployBuildInfo;
    private boolean includeEnvVars;

    @DataBoundConstructor
    public ArtifactoryIvyConfigurator(ServerDetails details, String username, String password, boolean deployArtifacts,
            boolean deployBuildInfo, boolean includeEnvVars) {
        this.details = details;
        this.username = username;
        this.password = Scrambler.scramble(password);
        this.deployArtifacts = deployArtifacts;
        this.deployBuildInfo = deployBuildInfo;
        this.includeEnvVars = includeEnvVars;
    }

    public ServerDetails getDetails() {
        return details;
    }

    public String getPassword() {
        return Scrambler.descramble(password);
    }

    public String getUsername() {
        return username;
    }

    public boolean isDeployArtifacts() {
        return deployArtifacts;
    }

    public boolean isDeployBuildInfo() {
        return deployBuildInfo;
    }

    public boolean isIncludeEnvVars() {
        return includeEnvVars;
    }

    public String getArtifactoryName() {
        return details != null ? details.artifactoryName : null;
    }

    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        final ArtifactoryServer artifactoryServer = getArtifactoryServer();
        build.setResult(Result.SUCCESS);
        return new AntIvyBuilderEnvironment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                Map<String, String> envVars = Maps.newHashMap();
                for (Map.Entry<String, String> entry : env.entrySet()) {
                    envVars.put(BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX + entry.getKey(), entry.getValue());
                }
                env.putAll(envVars);
                env.put(ClientProperties.PROP_CONTEXT_URL, artifactoryServer.getUrl());
                env.put(ClientProperties.PROP_PUBLISH_REPOKEY, getDetails().repositoryKey);
                env.put(ClientProperties.PROP_PUBLISH_USERNAME, artifactoryServer.getUserName());
                env.put(ClientProperties.PROP_PUBLISH_PASSWORD, artifactoryServer.getPassword());
                env.put(BuildInfoProperties.PROP_BUILD_AGENT_NAME, "Hudson");
                env.put(BuildInfoProperties.PROP_BUILD_AGENT_VERSION, build.getHudsonVersion());
                env.put(BuildInfoProperties.PROP_BUILD_NUMBER, build.getNumber() + "");
                env.put(BuildInfoProperties.PROP_BUILD_NAME, build.getProject().getName());
                env.put(BuildInfoProperties.PROP_PRINCIPAL, ActionableHelper.getHudsonPrincipal(build));
                env.put(BuildInfoConfigProperties.PROP_INCLUDE_ENV_VARS, String.valueOf(isIncludeEnvVars()));
                env.put(ClientProperties.PROP_PUBLISH_BUILD_INFO, String.valueOf(isDeployBuildInfo()));
                env.put(ClientProperties.PROP_PUBLISH_ARTIFACT, String.valueOf(isDeployArtifacts()));
            }

            @Override
            public String getAdditionalOpts() {
                File agentLib;
                try {
                    agentLib = Which.jarFile(Agent.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                StringBuilder extraAntOpts = new StringBuilder();
                extraAntOpts.append("-javaagent:").append(agentLib.getAbsolutePath()).append(" ");
                return extraAntOpts.toString();
            }

            @Override
            public String getAdditionalArgs() {
                final File agentFile;
                try {
                    agentFile = Which.jarFile(ArtifactoryIvySettingsConfigurator.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                StringBuilder targets = new StringBuilder();
                targets.append("-lib ").append(agentFile.getParentFile().getAbsolutePath()).append(" ");
                targets.append("-listener ").append("org.jfrog.build.extractor.listener.ArtifactoryBuildListener")
                        .append(" ");
                return targets.toString();
            }

            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                Result result = build.getResult();
                if (result == null) {
                    return false;
                }
                ArtifactoryRedeployPublisher publisher =
                        new ArtifactoryRedeployPublisher(getDetails(), true, username, getPassword(),
                                true);
                if (result.isBetterOrEqualTo(Result.SUCCESS)) {
                    build.getActions().add(new BuildInfoResultAction(publisher, build));
                    return true;
                }
                return false;
            }
        };
    }

    public ArtifactoryServer getArtifactoryServer() {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(getArtifactoryName())) {
                return server;
            }
        }
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(ArtifactoryIvyConfigurator.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return "hudson.ivy.IvyModuleSet".equals(item.getClass().getName());
        }

        @Override
        public String getDisplayName() {
            return "Deploy artifacts to Artifactory";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this, "ivy");
            save();
            return true;
        }

        /**
         * Returns the list of {@link org.jfrog.hudson.ArtifactoryServer} configured.
         *
         * @return can be empty but never null.
         */
        public List<ArtifactoryServer> getArtifactoryServers() {
            ArtifactoryBuilder.DescriptorImpl descriptor = (ArtifactoryBuilder.DescriptorImpl)
                    Hudson.getInstance().getDescriptor(ArtifactoryBuilder.class);
            return descriptor.getArtifactoryServers();
        }
    }
}
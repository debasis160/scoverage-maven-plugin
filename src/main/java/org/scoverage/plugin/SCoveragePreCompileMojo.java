/*
 * Copyright 2014-2019 Grzegorz Slowikowski (gslowikowski at gmail dot com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.scoverage.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import org.codehaus.plexus.util.StringUtils;

/**
 * Configures project for compilation with SCoverage instrumentation.
 * <br>
 * <br>
 * Supported compiler plugins:
 * <ul>
 * <li><a href="https://davidb.github.io/scala-maven-plugin/">net.alchim31.maven:scala-maven-plugin</a></li>
 * <li><a href="https://sbt-compiler-maven-plugin.github.io/sbt-compiler-maven-plugin/">com.google.code.sbt-compiler-maven-plugin:sbt-compiler-maven-plugin</a></li>
 * </ul>
 * <br>
 * This is internal mojo, executed in forked {@code scoverage} life cycle.
 * <br>
 *
 * @author <a href="mailto:gslowikowski@gmail.com">Grzegorz Slowikowski</a>
 * @since 1.0.0
 */
@Mojo( name = "pre-compile", defaultPhase = LifecyclePhase.GENERATE_RESOURCES )
public class SCoveragePreCompileMojo
    extends AbstractMojo
{

    /**
     * Allows SCoverage to be skipped.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.skip", defaultValue = "false" )
    private boolean skip;

    /**
     * Scala version used for compiler plugin artifact resolution.
     * <ul>
     * <li>if specified, and equals {@code 2.10} or starts with {@code 2.10.} - <b>{@code scalac-scoverage-plugin_2.10}</b> will be used</li>
     * <li>if specified, and equals {@code 2.11} or starts with {@code 2.11.} - <b>{@code scalac-scoverage-plugin_2.11}</b> will be used</li>
     * <li>if specified, and equals {@code 2.12} or starts with {@code 2.12.} - <b>{@code scalac-scoverage-plugin_2.12}</b> will be used</li>
     * <li>if specified, and equals {@code 2.13} or starts with {@code 2.13.} - <b>{@code scalac-scoverage-plugin_2.13}</b> will be used</li>
     * <li>if specified, but does not meet any of the above conditions or if not specified - plugin execution will be skipped</li>
     * </ul>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scala.version" )
    private String scalaVersion;

    /**
     * Directory where the coverage files should be written.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.dataDirectory", defaultValue = "${project.build.directory}/scoverage-data", required = true, readonly = true )
    private File dataDirectory;

    /**
     * Semicolon-separated list of regular expressions for packages to exclude, "(empty)" for default package.
     * <br>
     * <br>
     * Example:
     * <br>
     * {@code (empty);Reverse.*;.*AuthService.*;models\.data\..*}
     * <br>
     * <br>
     * See <a href="https://github.com/scoverage/sbt-scoverage#exclude-classes-and-packages">https://github.com/scoverage/sbt-scoverage#exclude-classes-and-packages</a> for additional documentation.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.excludedPackages", defaultValue = "" )
    private String excludedPackages;

    /**
     * Semicolon-separated list of regular expressions for source paths to exclude.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.excludedFiles", defaultValue = "" )
    private String excludedFiles;

    /**
     * See <a href="https://github.com/scoverage/sbt-scoverage#highlighting">https://github.com/scoverage/sbt-scoverage#highlighting</a>.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.highlighting", defaultValue = "true" )
    private boolean highlighting;

    /**
     * Force <a href="https://github.com/scoverage/scalac-scoverage-plugin">scalac-scoverage-plugin</a> version used.
     * <br>
     *
     * @since 1.0.0
     */
    @Parameter( property = "scoverage.scalacPluginVersion", defaultValue = "" )
    private String scalacPluginVersion;

    /**
     * Semicolon-separated list of project properties set in forked {@code scoverage} life cycle.
     * <br>
     * <br>
     * Example:
     * <br>
     * {@code prop1=val1;prop2=val2;prop3=val3}
     * <br>
     *
     * @since 1.4.0
     */
    @Parameter( property = "scoverage.additionalForkedProjectProperties", defaultValue = "" )
    private String additionalForkedProjectProperties;

    /**
     * Maven project to interact with.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * All Maven projects in the reactor.
     */
    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    private List<MavenProject> reactorProjects;

    /**
     * Artifact factory used to look up artifacts in the remote repository.
     */
    @Component
    private ArtifactFactory factory;

    /**
     * Artifact resolver used to resolve artifacts.
     */
    @Component
    private ArtifactResolver resolver;

    /**
     * Location of the local repository.
     */
    @Parameter( property = "localRepository", readonly = true, required = true )
    private ArtifactRepository localRepo;

    /**
     * Remote repositories used by the resolver
     */
    @Parameter( property = "project.remoteArtifactRepositories", readonly = true, required = true )
    private List<ArtifactRepository> remoteRepos;

    /**
     * List of artifacts this plugin depends on.
     */
    @Parameter( property = "plugin.artifacts", readonly = true, required = true )
    private List<Artifact> pluginArtifacts;

    /**
     * Configures project for compilation with SCoverage instrumentation.
     *
     * @throws MojoExecutionException if unexpected problem occurs
     */
    @Override
    public void execute() throws MojoExecutionException
    {
        if ( "pom".equals( project.getPackaging() ) )
        {
            getLog().info( "Skipping SCoverage execution for project with packaging type 'pom'" );
            //for aggragetor mojo - list of submodules: List<MavenProject> modules = project.getCollectedProjects();
            return;
        }

        if ( skip )
        {
            getLog().info( "Skipping Scoverage execution" );

            Properties projectProperties = project.getProperties();

            // for maven-resources-plugin (testResources), maven-compiler-plugin (testCompile),
            // sbt-compiler-maven-plugin (testCompile), scala-maven-plugin (testCompile),
            // maven-surefire-plugin and scalatest-maven-plugin
            setProperty( projectProperties, "maven.test.skip", "true" );
            // for scalatest-maven-plugin and specs2-maven-plugin
            setProperty( projectProperties, "skipTests", "true" );

            return;
        }

        long ts = System.currentTimeMillis();

        String scalaBinaryVersion = null;
        String resolvedScalaVersion = resolveScalaVersion();
        if ( resolvedScalaVersion != null )
        {
            if ( "2.10".equals( resolvedScalaVersion ) || resolvedScalaVersion.startsWith( "2.10." ) )
            {
                scalaBinaryVersion = "2.10";
            }
            else if ( "2.11".equals( resolvedScalaVersion ) || resolvedScalaVersion.startsWith( "2.11." ) )
            {
                scalaBinaryVersion = "2.11";
            }
            else if ( "2.12".equals( resolvedScalaVersion ) || resolvedScalaVersion.startsWith( "2.12." ) )
            {
                scalaBinaryVersion = "2.12";
            }
            else if ( "2.13".equals( resolvedScalaVersion ) || resolvedScalaVersion.startsWith( "2.13." ) )
            {
                scalaBinaryVersion = "2.13";
            }
            else
            {
                getLog().warn( String.format( "Skipping SCoverage execution - unsupported Scala version \"%s\"",
                                              resolvedScalaVersion ) );
                return;
            }
        }
        else
        {
            getLog().warn( "Skipping SCoverage execution - Scala version not set" );
            return;
        }

        Map<String, String> additionalProjectPropertiesMap = null;
        if ( additionalForkedProjectProperties != null && !additionalForkedProjectProperties.isEmpty() )
        {
            String[] props = additionalForkedProjectProperties.split( ";" );
            additionalProjectPropertiesMap = new HashMap<String, String>( props.length );
            for ( String propVal: props )
            {
                String[] tmp = propVal.split( "=", 2 );
                if ( tmp.length == 2 )
                {
                    String propName = tmp[ 0 ].trim();
                    String propValue = tmp[ 1 ].trim();
                    additionalProjectPropertiesMap.put( propName, propValue );
                }
                else
                {
                    getLog().warn( String.format( "Skipping invalid additional forked project property \"%s\", must be in \"key=value\" format",
                            propVal ) );

                }
            }
        }

        SCoverageForkedLifecycleConfigurator.afterForkedLifecycleEnter( project, reactorProjects, additionalProjectPropertiesMap );

        try
        {
            Artifact pluginArtifact = getScalaScoveragePluginArtifact( scalaBinaryVersion, resolvedScalaVersion );
            Artifact runtimeArtifact = getScalaScoverageRuntimeArtifact( scalaBinaryVersion );

            if ( pluginArtifact == null )
            {
                return; // scoverage plugin will not be configured
            }

            addScoverageDependenciesToClasspath( runtimeArtifact );

            String arg = DATA_DIR_OPTION + dataDirectory.getAbsolutePath();
            String _scalacOptions = quoteArgument( arg );
            String addScalacArgs = arg;

            if ( !StringUtils.isEmpty( excludedPackages ) )
            {
                arg = EXCLUDED_PACKAGES_OPTION + excludedPackages.replace( "(empty)", "<empty>" );
                _scalacOptions = _scalacOptions + SPACE + quoteArgument( arg );
                addScalacArgs = addScalacArgs + PIPE + arg;
            }

            if ( !StringUtils.isEmpty( excludedFiles ) )
            {
                arg = EXCLUDED_FILES_OPTION + excludedFiles;
                _scalacOptions = _scalacOptions + SPACE + quoteArgument( arg );
                addScalacArgs = addScalacArgs + PIPE + arg;
            }

            if ( highlighting )
            {
                _scalacOptions = _scalacOptions + SPACE + "-Yrangepos";
                addScalacArgs = addScalacArgs + PIPE + "-Yrangepos";
            }

            String _scalacPlugins =
                String.format( "%s:%s:%s", pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(),
                               pluginArtifact.getVersion() );

            arg = PLUGIN_OPTION + pluginArtifact.getFile().getAbsolutePath();
            addScalacArgs = addScalacArgs + PIPE + arg;

            Properties projectProperties = project.getProperties();

            // for sbt-compiler-maven-plugin (version 1.0.0-beta5+)
            setProperty( projectProperties, "sbt._scalacOptions", _scalacOptions );
            // for sbt-compiler-maven-plugin (version 1.0.0-beta5+)
            setProperty( projectProperties, "sbt._scalacPlugins", _scalacPlugins );
            // for scala-maven-plugin (version 3.0.0+)
            setProperty( projectProperties, "addScalacArgs", addScalacArgs );
            // for scala-maven-plugin (version 3.1.0+)
            setProperty( projectProperties, "analysisCacheFile",
                         "${project.build.directory}/scoverage-analysis/compile" );
            // for maven-surefire-plugin and scalatest-maven-plugin
            setProperty( projectProperties, "maven.test.failure.ignore", "true" );

            // for maven-jar-plugin
            // VERY IMPORTANT! Prevents from overwriting regular project artifact file
            // with instrumented one during "integration-check" or "integration-report" execution.
            project.getBuild().setFinalName( "scoverage-" + project.getBuild().getFinalName() );

            saveSourceRootsToFile();
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "SCoverage preparation failed", e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "SCoverage preparation failed", e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "SCoverage preparation failed", e );
        }

        long te = System.currentTimeMillis();
        getLog().debug( String.format( "Mojo execution time: %d ms", te - ts ) );
    }

    // Private utility methods

    private static final String SCALA_LIBRARY_GROUP_ID = "org.scala-lang";
    private static final String SCALA_LIBRARY_ARTIFACT_ID = "scala-library";

    private static final String DATA_DIR_OPTION = "-P:scoverage:dataDir:";
    private static final String EXCLUDED_PACKAGES_OPTION = "-P:scoverage:excludedPackages:";
    private static final String EXCLUDED_FILES_OPTION = "-P:scoverage:excludedFiles:";
    private static final String PLUGIN_OPTION = "-Xplugin:";

    private static final char DOUBLE_QUOTE = '\"';
    private static final char SPACE = ' ';
    private static final char PIPE = '|';

    private String quoteArgument( String arg )
    {
        return arg.indexOf( SPACE ) >= 0 ? DOUBLE_QUOTE + arg + DOUBLE_QUOTE : arg;
    }

    private String resolveScalaVersion()
    {
        String result = scalaVersion;
        if ( result == null )
        {
            // check project direct dependencies (transitive dependencies cannot be checked in this Maven lifecycle phase)
            @SuppressWarnings( "unchecked" )
            List<Dependency> dependencies = project.getDependencies();
            for ( Dependency dependency: dependencies )
            {
                if ( SCALA_LIBRARY_GROUP_ID.equals( dependency.getGroupId() )
                    && SCALA_LIBRARY_ARTIFACT_ID.equals( dependency.getArtifactId() ) )
                {
                    result = dependency.getVersion();
                    break;
                }
            }
        }
        return result;
    }

    private void setProperty( Properties projectProperties, String propertyName, String newValue )
    {
        if ( projectProperties.containsKey( propertyName ) )
        {
            String oldValue = projectProperties.getProperty( propertyName );
            projectProperties.put( "scoverage.backup." + propertyName, oldValue );
        }
        else
        {
            projectProperties.remove( "scoverage.backup." + propertyName );
        }

        if ( newValue != null )
        {
            projectProperties.put( propertyName, newValue );
        }
        else
        {
            projectProperties.remove( propertyName );
        }
    }

    private Artifact getScalaScoveragePluginArtifact( String scalaMainVersion, String resolvedScalaVersion )
        throws ArtifactNotFoundException, ArtifactResolutionException
    {
        Artifact result = null;

        String resolvedScalacPluginVersion = scalacPluginVersion;
        if ( resolvedScalacPluginVersion == null || "".equals( resolvedScalacPluginVersion ) )
        {
            for ( Artifact artifact : pluginArtifacts )
            {
                if ( "org.scoverage".equals( artifact.getGroupId() )
                    && "scalac-scoverage-plugin_2.12".equals( artifact.getArtifactId() ) )
                {
                    if ( "2.12".equals( scalaMainVersion ) )
                    {
                        return artifact; // shortcut, use the same artifact plugin uses
                    }
                    resolvedScalacPluginVersion = artifact.getVersion();
                    break;
                }
            }
        }

        try
        {
            // Look for plugin artifact matching the scala version (full form like 2.12.14)
            // If not found then look for artifact based on major version like 2.12
            result =
                    getResolvedArtifact( "org.scoverage", "scalac-scoverage-plugin_" + resolvedScalaVersion,
                            resolvedScalacPluginVersion );
        } catch (ArtifactNotFoundException e)
        {
            result =
                    getResolvedArtifact( "org.scoverage", "scalac-scoverage-plugin_" + scalaMainVersion,
                            resolvedScalacPluginVersion );
        }

        return result;
    }

    private Artifact getScalaScoverageRuntimeArtifact( String scalaMainVersion )
        throws ArtifactNotFoundException, ArtifactResolutionException
    {
        Artifact result = null;

        String resolvedScalacRuntimeVersion = scalacPluginVersion;
        if ( resolvedScalacRuntimeVersion == null || "".equals( resolvedScalacRuntimeVersion ) )
        {
            for ( Artifact artifact : pluginArtifacts )
            {
                if ( "org.scoverage".equals( artifact.getGroupId() )
                    && "scalac-scoverage-plugin_2.12".equals( artifact.getArtifactId() ) )
                {
                    resolvedScalacRuntimeVersion = artifact.getVersion();
                    break;
                }
            }
        }

        result =
            getResolvedArtifact( "org.scoverage", "scalac-scoverage-runtime_" + scalaMainVersion,
                                 resolvedScalacRuntimeVersion );
        return result;
    }

    /**
     * We need to tweak our test classpath for Scoverage.
     *
     * @throws MojoExecutionException
     */
    private void addScoverageDependenciesToClasspath( Artifact scalaScoveragePluginArtifact )
        throws MojoExecutionException
    {
        @SuppressWarnings( "unchecked" )
        Set<Artifact> set = new LinkedHashSet<Artifact>( project.getDependencyArtifacts() );
        set.add( scalaScoveragePluginArtifact );
        project.setDependencyArtifacts( set );
    }

    private Artifact getResolvedArtifact( String groupId, String artifactId, String version )
        throws ArtifactNotFoundException, ArtifactResolutionException
    {
        Artifact artifact = factory.createArtifact( groupId, artifactId, version, Artifact.SCOPE_COMPILE, "jar" );
        resolver.resolve( artifact, remoteRepos, localRepo );
        return artifact;
    }

    private void saveSourceRootsToFile() throws IOException
    {
        List<String> sourceRoots = project.getCompileSourceRoots();
        if ( !sourceRoots.isEmpty() )
        {
            if ( !dataDirectory.exists() && !dataDirectory.mkdirs() )
            {
                throw new IOException( String.format( "Cannot create \"%s\" directory ",
                        dataDirectory.getAbsolutePath() ) );
            }
            File sourceRootsFile = new File( dataDirectory, "source.roots" );
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter( new FileOutputStream( sourceRootsFile ), "UTF-8" ) );
            try
            {
                for ( String sourceRoot: sourceRoots )
                {
                    writer.write( sourceRoot );
                    writer.newLine();
                }
            }
            finally
            {
                writer.close();
            }
        }
    }

}

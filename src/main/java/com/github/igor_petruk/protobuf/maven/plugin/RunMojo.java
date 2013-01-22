/*
 * Copyright 2012, by Yet another Protobuf Maven Plugin Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.igor_petruk.protobuf.maven.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.codehaus.plexus.util.FileUtils;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Scanner;

/**
 * @goal run
 * @phase generate-sources
 * @requiresDependencyResolution
 */
public class RunMojo extends AbstractMojo {

    private static final String DEFAULT_INPUT_DIR= "/src/main/protobuf/".replace('/',File.separatorChar);
    private static final String VERSION_KEY="--version";
    private static final int VALID_VERSION_EXIT_CODE=1;

    /**
     * The Maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The artifact repository to use.
     *
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * The artifact factory to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactFactory artifactFactory;

    /**
     * The artifact metadata source to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactMetadataSource artifactMetadataSource;

    /**
     * The artifact collector to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactCollector artifactCollector;

    /**
     * The dependency tree builder to use.
     *
     * @component
     * @required
     * @readonly
     */
    private DependencyTreeBuilder dependencyTreeBuilder;

    /** @component */
    private BuildContext buildContext;

    /**
     * Input directories that have *.protoc files (or the configured extension).
     * If none specified then <b>src/main/protobuf</b> is used.
     * @parameter expression="${inputDirectories}"
     */
    private File[] inputDirectories;

    /**
     * This parameter lets you specify additional include paths to protoc.
     * @parameter expression="${includeDirectories}"
     */
    private File[] includeDirectories;

    /**
     * If this parameter is set to "true" output folder is cleaned prior to build.
     * This will not let old and new classes coexist after package or class
     * rename in your IDE cache or after non-clean rebuild.
     * Set this to "false" if you are doing multiple plugin invocations per build
     * and it is important to preserve output folder contents
     * @parameter expression="${cleanOutputFolder}" default-value="true"
     * @required
     */
    private boolean cleanOutputFolder;

    /**
     * Specifies a mode for plugin whether it should
     * add outputDirectory to sources that are going to be compiled
     * Can be "main", "test" or "none"
     * @parameter expression="${addSources}" default-value="main"
     * @required
     */
    private String addSources;

    /**
     * Output directory, that generated java files would be stored
     * Defaults to "${project.build.directory}/generated-sources/protobuf"
     * or "${project.build.directory}/generated-test-sources/protobuf" depending
     * addSources parameter
     * @parameter expression="${outputDirectory}"
     */
    private File outputDirectory;

    /**
     * Default extension for protobuf files
     * @parameter expression="${extension}" default-value=".proto"
     * @required
     */
    private String extension;

    /**
     * Setting to "true" disables version check between 'protoc' and the protobuf library used by module
     * @parameter expression="${ignoreVersions}" default-value="false"
     * @required
     */
    private boolean ignoreVersions;

    /**
     * This parameter allows to override the protoc command that is going to be used.
     * @parameter expression="${protocCommand}" default-value="protoc"
     */
    private String protocCommand;

    /**
     * This parameter allows to override protobuf library groupId
     * @parameter expression="${protobufGroupId}" default-value="com.google.protobuf"
     */
    private String protobufGroupId;

    /**
     * This parameter allows to override protobuf library artifactId
     * @parameter expression="${protobufArtifactId}" default-value="protobuf-java"
     */
    private String protobufArtifactId;

    public void execute() throws MojoExecutionException
    {
        if (project.getPackaging()!=null &&
                "pom".equals(project.getPackaging().toLowerCase())){
            getLog().info("Skipping 'pom' packaged project");
            return;
        }
        String dependencyVersion = getProtobufVersion();
        getLog().info("Protobuf dependency version " + dependencyVersion);
        String executableVersion = detectProtobufVersion();
        if (executableVersion==null){
            throw new MojoExecutionException("Unable to find '"+protocCommand+"'");
        }
        getLog().info("'protoc' executable version "+executableVersion);
        if (!ignoreVersions){
            if (dependencyVersion==null){
                throw new MojoExecutionException("Protobuf library dependency not found in pom: "+protobufGroupId+":" +protobufArtifactId);
            }
            if (!dependencyVersion.startsWith(executableVersion)){
                throw new MojoExecutionException("Protobuf installation version does not match Protobuf library version");
            }
        }
        // Compatablity measures
        addSources = addSources.toLowerCase().trim();
        if ("true".equals(addSources)){
            addSources = "main";
        }

        if (outputDirectory==null){
            String subdir = "generated-"+("test".equals(addSources)?"test-":"")+"sources";
            outputDirectory = new File(project.getBuild().getDirectory()+File.separator+subdir+File.separator);
        }

        performProtoCompilation();
    }

    private void performProtoCompilation() throws MojoExecutionException{
        if (includeDirectories!=null && includeDirectories.length>0){
            getLog().info("Include directories:");
            for (File include: includeDirectories){
                getLog().info("    "+include);
            }
        }
        getLog().info("Input directories:");
        for (File input: inputDirectories){
            getLog().info("    "+input);
        }
        if (includeDirectories==null || inputDirectories.length==0){
            File inputDir = new File(project.getBasedir().getAbsolutePath() + DEFAULT_INPUT_DIR);
            getLog().info("    "+inputDir+" (using default)");
            inputDirectories = new File[]{inputDir};
        }

        getLog().info("Output directory: "+outputDirectory);
        File f = outputDirectory;
        if ( !f.exists() )
        {
            getLog().info(f+" does not exist. Creating...");
            f.mkdirs();
        }
        if (cleanOutputFolder){
            try {
                getLog().info("Cleaning "+f);
                FileUtils.cleanDirectory(f);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final ProtoFileFilter PROTO_FILTER = new ProtoFileFilter(extension);

        for (File input: inputDirectories){
            if (input==null){
                continue;
            }
            getLog().info("Directory "+input);
            if (input.exists() && input.isDirectory()){
                File[] files = input.listFiles(PROTO_FILTER);
                for (File file: files){
                    if (cleanOutputFolder || buildContext.hasDelta(file.getPath())){
                        processFile(file, outputDirectory);
                    }else{
                        getLog().info("Not changed "+file);
                    }
                }
            }else{
                if (input.exists())
                    getLog().warn(input+" is not a directory");
                else
                    getLog().warn(input+" does not exist");
            }
        }
        boolean mainAddSources = "main".endsWith(addSources);
        boolean testAddSources = "test".endsWith(addSources);
        if (mainAddSources){
            getLog().info("Adding generated classes to classpath");
            project.addCompileSourceRoot( outputDirectory.getAbsolutePath() );
        }
        if (testAddSources){
            getLog().info("Adding generated classes to test classpath");
            project.addTestCompileSourceRoot( outputDirectory.getAbsolutePath() );
        }
        if (mainAddSources || testAddSources){
            buildContext.refresh(outputDirectory);
        }
    }
    
    private void processFile(File file, File outputDir) throws MojoExecutionException{
        getLog().info("    Processing "+file.getName());
        Runtime runtime = Runtime.getRuntime();
        Collection<String> cmd = buildCommand(file, outputDir);
        try {
            Process process = runtime.exec(cmd.toArray(new String[0]));
            if (process.waitFor() != 0) {
                printErrorAndThrow(process, " for " + file);
            }
        }catch (InterruptedException e){
            throw new MojoExecutionException("Interrupted",e);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to execute protoc for "+file, e);
        }
    }

    private Collection<String> buildCommand(File file, File outputDir) throws MojoExecutionException {
        Collection<String> cmd = new LinkedList<String>();
        cmd.add(protocCommand);
        populateIncludes(cmd);
        cmd.add("-I" + file.getParentFile().getAbsolutePath());
        cmd.add("--java_out=" + outputDir);
        cmd.add(file.toString());
        return cmd;
    }

    private void populateIncludes(Collection<String> args) throws MojoExecutionException {
        for (File include : includeDirectories) {
            if (!include.exists())
                throw new MojoExecutionException("Include path '" + include.getPath() + "' does not exist");
            if (!include.isDirectory())
                throw new MojoExecutionException("Include path '" + include.getPath() + "' is not a directory");
            args.add("-I" + include.getPath());
        }
    }

    private String getProtobufVersion() throws MojoExecutionException{
        try {
            ArtifactFilter artifactFilter = null;
            DependencyNode node = dependencyTreeBuilder.buildDependencyTree(project,localRepository,
                    artifactFactory,
                    artifactMetadataSource,
                    null,
                    artifactCollector
            );
            return traverseNode(node);

        } catch (DependencyTreeBuilderException e) {
            throw new MojoExecutionException("Unable to traverse dependency tree", e);
        }
    }

    private String detectProtobufVersion() throws MojoExecutionException {
        try {
            Runtime runtime = Runtime.getRuntime();
            Process process = runtime.exec(protocVersionCommand());

            if (process.waitFor() != VALID_VERSION_EXIT_CODE) {
                printErrorAndThrow(process);
            } else {
                Scanner scanner = new Scanner(process.getInputStream());
                String[] version = scanner.nextLine().split(" ");
                return version[1];
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot execute '" + protocCommand + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    private String[] protocVersionCommand() {
        return new String[]{protocCommand, VERSION_KEY};
    }

    private void printErrorAndThrow(Process process, String exceptionMessage) throws MojoExecutionException {
        Scanner scanner = new Scanner(process.getErrorStream());
        while (scanner.hasNextLine()) {
            getLog().error("    " + scanner.nextLine());
        }

        throw new MojoExecutionException("'protoc' failed" + exceptionMessage + ". Exit code " + process.exitValue());
    }

    private void printErrorAndThrow(Process process) throws MojoExecutionException {
        printErrorAndThrow(process, "");
    }

    private String traverseNode(DependencyNode node) {
        Artifact artifact = node.getArtifact();
        if ((protobufGroupId.equals(artifact.getGroupId())
        && (protobufArtifactId.equals(artifact.getArtifactId())))){
            return artifact.getVersion();
        }
        for (Object o: node.getChildren()){
            DependencyNode child = (DependencyNode)o;
            String result = traverseNode(child);
            if (result!=null)
                return result;
        }
        return null;
    }
}

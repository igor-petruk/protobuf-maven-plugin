package com.github.igor_petruk.protobuf.maven.plugin;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Scanner;

/**
 * @goal run
 * @phase generate-sources
 * @requiresDependencyResolution
 */
public class MyMojo extends AbstractMojo {

    private static final String DEFAULT_INPUT_DIR= "/src/main/protobuf/".replace('/',File.separatorChar);
    private static final String VERSION_KEY="--version";

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
     * @required
     */
    private File[] inputDirectories;

    /**
     * Should plugin add outputDirectory to sources that are going to be compiled
     * @parameter expression="${addSources}" default-value="true"
     * @required
     */
    private boolean addSources;

    /**
     * Output directory, that generated java files would be stored
     * @parameter expression="${outputDirectory}" default-value="${project.build.directory}/generated-sources/protobuf"
     * @required
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
        performProtoCompilation();
    }

    private void performProtoCompilation() throws MojoExecutionException{
        File f = outputDirectory;
        if ( !f.exists() )
        {
            f.mkdirs();
        }
        if (inputDirectories.length==0){
            File inputDir = new File(project.getBasedir().getAbsolutePath() + DEFAULT_INPUT_DIR);
            inputDirectories = new File[]{inputDir};
        }
        getLog().info("Input directories:");
        for (File input: inputDirectories){
            getLog().info("    "+input);
        }
        getLog().info("Output directory: "+outputDirectory);
        final ProtoFileFilter PROTO_FILTER = new ProtoFileFilter(extension);

        for (File input: inputDirectories){
            getLog().info("Directory "+input);
            File[] files = input.listFiles(PROTO_FILTER);
            for (File file: files){
                if (buildContext.hasDelta(file.getPath())){
                    processFile(file, outputDirectory);
                }else{
                    getLog().info("Not changed "+file);
                }
            }
        }
        if (addSources){
            project.addCompileSourceRoot( outputDirectory.getAbsolutePath() );
            buildContext.refresh(outputDirectory);
        }
    }
    
    private void processFile(File file, File outputDir) throws MojoExecutionException{
        getLog().info("    Processing "+file.getName());
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(new String[]{
                    protocCommand,
                    "--proto_path="+file.getParentFile().getAbsolutePath(),
                    "--java_out="+outputDir,
                file.toString()
            });
            int result = process.waitFor();
            if (result!=0){
                Scanner scanner = new Scanner(process.getErrorStream());
                while (scanner.hasNextLine()){
                    getLog().info("    " + scanner.nextLine());
                }
                throw new MojoExecutionException("'protoc' failed for "+file+". Exit code "+result);
            }
        }catch (InterruptedException e){
            throw new MojoExecutionException("Interrupted",e);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to execute protoc for "+file, e);
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
    
    private String detectProtobufVersion() throws MojoExecutionException{
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(new String[]{
                    protocCommand,VERSION_KEY});
            Scanner scanner = new Scanner(process.getInputStream());
            String[] version = scanner.nextLine().split(" ");
            return version[1];
        } catch (IOException e) {
            return null;
        }
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

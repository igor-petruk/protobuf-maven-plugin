package com.petruk.protobuf;

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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

/**
 * @goal run
 * @phase generate-sources
 * @requiresDependencyResolution
 */
public class MyMojo extends AbstractMojo {

    private static final String PROTOBUF_GROUPID="com.google.protobuf";
    private static final String PROTOBUF_ARTIFACTID="protobuf-java";
    private static final String PROTOC="protoc";
    private static final String VERSION_KEY="--version";
    private static final ProtoFileFilter PROTO_FILTER =
            new ProtoFileFilter(".proto");

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

    /**
     * Location of the file.
     * @parameter expression="${run.inputDirectories}"
     * @required
     */
    private File[] inputDirectories;

    /**
     * Location of the file.
     * @parameter expression="${run.outputDirectory}"
     * @required
     */
    private File outputDirectory;

    public void execute() throws MojoExecutionException
    {
        String dependencyVersion = getProtobufVersion();
        getLog().info("Protobuf dependency version " + dependencyVersion);
        String executableVersion = detectProtobufVersion();
        if (executableVersion==null){
            throw new MojoExecutionException("Unable to find '"+PROTOC+"'");
        }
        getLog().info("'protoc' executable version "+executableVersion);
        if (!dependencyVersion.startsWith(executableVersion)){
            throw new MojoExecutionException("Protobuf installation version does not match Protobuf library version");
        }
        performProtoCompilation();
    }

    private void performProtoCompilation() throws MojoExecutionException{
        File f = outputDirectory;
        if ( !f.exists() )
        {
            f.mkdirs();
        }
        for (File input: inputDirectories){
            getLog().info("Directory "+input);
            File[] files = input.listFiles(PROTO_FILTER);
            for (File file: files){
                processFile(file, outputDirectory);
            }
        }
    }
    
    private void processFile(File file, File outputDir) throws MojoExecutionException{
        getLog().info("    Processing "+file.getName());
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(new String[]{
                PROTOC,
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
                    PROTOC,VERSION_KEY});
            Scanner scanner = new Scanner(process.getInputStream());
            String[] version = scanner.nextLine().split(" ");
            return version[1];
        } catch (IOException e) {
            return null;
        }
    }

    private String traverseNode(DependencyNode node) {
        Artifact artifact = node.getArtifact();
        if ((PROTOBUF_GROUPID.equals(artifact.getGroupId())
        && (PROTOBUF_ARTIFACTID.equals(artifact.getArtifactId())))){
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

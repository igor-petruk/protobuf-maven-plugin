package com.github.igor_petruk.protobuf.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * A protobuf version is something like major.minor.revision. For example, if
 * the version is 2.4.1, major = 2, minor = 4, revision = 1.
 * 
 */
public class ProtoVersion {

    public static enum VersionValidationStrategy {
        major, // Only validate the major part. If the major parts of two
               // versions are same, they pass the validation.
        minor, // Validate the major and minor parts. If the major and minor
               // parts of two versions are same, they pass the validation.
        all; // Validate if two versions are same.

        public static VersionValidationStrategy fromString(String strategy)
                throws MojoExecutionException {
            if (strategy.equals(major.name())) {
                return major;
            }
            if (strategy.equals(minor.name())) {
                return minor;
            }
            if (strategy.equals(all.name())) {
                return all;
            }
            throw new MojoExecutionException(
                    "Unrecognized protobuf version validation strategy: "
                            + strategy);
        }
    }

    private String major;
    private String minor;
    private String revision;

    public ProtoVersion(String version) throws MojoExecutionException {
        String[] splits = version.split("\\.", 3);
        if (splits.length == 3) {
            major = splits[0];
            minor = splits[1];
            revision = splits[2];
        } else if (splits.length == 2) {
            major = splits[0];
            minor = splits[1];
            revision = "";
        } else if (splits.length == 1) {
            major = splits[0];
            minor = "";
            revision = "";
        } else {
            throw new MojoExecutionException("Unrecognized protobuf version: "
                    + version);
        }
    }

    public String getMajor() {
        return major;
    }

    public String getMinor() {
        return minor;
    }

    public String getRest() {
        return revision;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + revision;
    }

    public static boolean validate(VersionValidationStrategy strategy,
            ProtoVersion v1, ProtoVersion v2) throws MojoExecutionException {
        switch (strategy) {
        case major:
            return v1.major.equals(v2.major);
        case minor:
            return v1.major.equals(v2.major) && v1.minor.equals(v2.minor);
        case all:
            return v1.major.equals(v2.major) && v1.minor.equals(v2.minor)
                    && v1.revision.equals(v2.revision);
        }
        throw new MojoExecutionException(
                "Unrecognized protobuf version validation strategy: "
                        + strategy);
    }

}

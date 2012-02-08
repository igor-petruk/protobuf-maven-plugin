package com.github.igor_petruk.protobuf.maven.plugin;

import java.io.File;
import java.io.FilenameFilter;

/**
 * User: Igor Petruk
 * Date: 07.02.12
 * Time: 17:01
 */
public class ProtoFileFilter implements FilenameFilter{
    String extension;
    
    public ProtoFileFilter(String extension) {
        this.extension = extension;
    }

    @Override
    public boolean accept(File dir, String name) {
        return name.endsWith(extension);
    }
}

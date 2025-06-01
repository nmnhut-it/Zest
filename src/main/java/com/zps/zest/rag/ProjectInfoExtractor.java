package com.zps.zest.rag;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts project information including build system, dependencies, and structure.
 */
public class ProjectInfoExtractor {
    private final Project project;
    
    public ProjectInfoExtractor(Project project) {
        this.project = project;
    }
    
    public ProjectInfo extractProjectInfo() {
        ProjectInfo info = new ProjectInfo();
        
        // Detect build system
        VirtualFile projectRoot = project.getBaseDir();
        if (projectRoot != null) {
            if (projectRoot.findChild("pom.xml") != null) {
                info.setBuildSystem("Maven");
                extractMavenInfo(info, projectRoot.findChild("pom.xml"));
            } else if (projectRoot.findChild("build.gradle") != null || 
                       projectRoot.findChild("build.gradle.kts") != null) {
                info.setBuildSystem("Gradle");
                VirtualFile buildFile = projectRoot.findChild("build.gradle");
                if (buildFile == null) {
                    buildFile = projectRoot.findChild("build.gradle.kts");
                }
                extractGradleInfo(info, buildFile);
            }
            
            // Check for lib folder
            VirtualFile libFolder = projectRoot.findChild("lib");
            if (libFolder != null && libFolder.isDirectory()) {
                extractLibraries(info, libFolder);
            }
        }
        
        // Count source files
        List<VirtualFile> sourceFiles = findAllSourceFiles();
        info.setTotalSourceFiles(sourceFiles.size());
        
        // Determine main language
        int javaCount = 0;
        int kotlinCount = 0;
        for (VirtualFile file : sourceFiles) {
            if (file.getName().endsWith(".java")) javaCount++;
            else if (file.getName().endsWith(".kt")) kotlinCount++;
        }
        
        if (javaCount > kotlinCount) {
            info.setMainLanguage("Java");
        } else if (kotlinCount > 0) {
            info.setMainLanguage("Kotlin");
        } else {
            info.setMainLanguage("Unknown");
        }
        
        return info;
    }
    
    public List<VirtualFile> findAllSourceFiles() {
        List<VirtualFile> sourceFiles = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        
        fileIndex.iterateContent(fileOrDir -> {
            if (!fileOrDir.isDirectory() && isSourceFile(fileOrDir)) {
                sourceFiles.add(fileOrDir);
            }
            return true;
        });
        
        return sourceFiles;
    }
    
    private boolean isSourceFile(VirtualFile file) {
        String name = file.getName();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts");
    }
    
    private void extractMavenInfo(ProjectInfo info, VirtualFile pomFile) {
        try {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(pomFile);
            if (psiFile instanceof XmlFile) {
                XmlFile xmlFile = (XmlFile) psiFile;
                XmlTag rootTag = xmlFile.getRootTag();
                if (rootTag != null && "project".equals(rootTag.getName())) {
                    // Extract dependencies
                    XmlTag dependenciesTag = rootTag.findFirstSubTag("dependencies");
                    if (dependenciesTag != null) {
                        XmlTag[] dependencyTags = dependenciesTag.findSubTags("dependency");
                        for (XmlTag dep : dependencyTags) {
                            String groupId = getTagValue(dep, "groupId");
                            String artifactId = getTagValue(dep, "artifactId");
                            String version = getTagValue(dep, "version");
                            
                            if (groupId != null && artifactId != null) {
                                String depString = groupId + ":" + artifactId;
                                if (version != null) {
                                    depString += ":" + version;
                                }
                                info.addDependency(depString);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error but continue
        }
    }
    
    private void extractGradleInfo(ProjectInfo info, VirtualFile buildFile) {
        try {
            String content = new String(buildFile.contentsToByteArray());
            
            // Extract dependencies using regex patterns
            Pattern depPattern = Pattern.compile(
                "(?:implementation|compile|api|testImplementation)\\s*[\\(\\s]*['\"]([^'\"]+)['\"]",
                Pattern.MULTILINE
            );
            
            Matcher matcher = depPattern.matcher(content);
            while (matcher.find()) {
                String dependency = matcher.group(1);
                info.addDependency(dependency);
            }
            
            // Also look for Kotlin specific patterns
            if (buildFile.getName().endsWith(".kts")) {
                Pattern kotlinDepPattern = Pattern.compile(
                    "(?:implementation|compile|api|testImplementation)\\s*\\(['\"]([^'\"]+)['\"]\\)",
                    Pattern.MULTILINE
                );
                
                matcher = kotlinDepPattern.matcher(content);
                while (matcher.find()) {
                    String dependency = matcher.group(1);
                    info.addDependency(dependency);
                }
            }
        } catch (IOException e) {
            // Log error but continue
        }
    }
    
    private void extractLibraries(ProjectInfo info, VirtualFile libFolder) {
        VirtualFile[] children = libFolder.getChildren();
        for (VirtualFile child : children) {
            if (!child.isDirectory() && child.getName().endsWith(".jar")) {
                info.addLibrary(child.getName());
            }
        }
    }
    
    private String getTagValue(XmlTag parent, String tagName) {
        XmlTag tag = parent.findFirstSubTag(tagName);
        return tag != null ? tag.getValue().getText() : null;
    }
}

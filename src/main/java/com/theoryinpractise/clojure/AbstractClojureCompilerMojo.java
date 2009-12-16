/*
 * Created by IntelliJ IDEA.
 * User: amrk
 * Date: Apr 18, 2009
 * Time: 1:08:16 PM
 */
package com.theoryinpractise.clojure;

import org.apache.commons.exec.Executor;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ExecuteException;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import java.lang.reflect.Method;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public abstract class AbstractClojureCompilerMojo extends AbstractMojo {

    /**
     * Base directory of the project.
     *
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    protected File baseDirectory;

    /**
     * Project classpath.
     *
     * @parameter default-value="${project.compileClasspathElements}"
     * @required
     * @readonly
     */
    protected List<String> classpathElements;

    /**
     * Location of the file.
     *
     * @parameter default-value="${project.build.outputDirectory}"
     * @required
     */
    protected File outputDirectory;

    /**
     * Location of the source files.
     *
     * @parameter
     */
    private File[] sourceDirectories = new File[]{
            new File(baseDirectory, "src/main/clojure")
    };

    /**
     * Location of the source files.
     *
     * @parameter
     */
    private File[] testSourceDirectories = new File[]{
            new File(baseDirectory, "src/test/clojure")
    };

    /**
     * Location of the source files.
     *
     * @parameter default-value="${project.build.testSourceDirectory}"
     * @required
     */
    protected File baseTestSourceDirectory;

    /**
     * Location of the generated source files.
     *
     * @parameter default-value="${project.build.outputDirectory}/../generated-sources"
     * @required
     */
    protected File generatedSourceDirectory;

    /**
     * Should we compile all namespaces or only those defined?
     *
     * @parameter defaut-value="false"
     */
    protected boolean compileDeclaredNamespaceOnly;

    /**
     * A list of namespaces to compile
     *
     * @parameter
     */
    protected String[] namespaces;

    /**
     * Classes to put onto the command line before the main class
     *
     * @parameter
     */
    private List<String> prependClasses;

    protected String[] discoverNamespaces() throws MojoExecutionException {
        return new NamespaceDiscovery(getLog(), compileDeclaredNamespaceOnly).discoverNamespacesIn(namespaces, sourceDirectories);
    }

    public enum SourceDirectory { COMPILE, TEST };

    public File[] getSourceDirectories(SourceDirectory... sourceDirectoryTypes) {
        List<File> dirs = new ArrayList<File>();

        if (Arrays.asList(sourceDirectoryTypes).contains(SourceDirectory.COMPILE)) {
            dirs.add(generatedSourceDirectory);                       
            dirs.addAll(Arrays.asList(sourceDirectories));
        }
        if (Arrays.asList(sourceDirectoryTypes).contains(SourceDirectory.TEST)) {
            dirs.add(baseTestSourceDirectory);
            dirs.addAll(Arrays.asList(testSourceDirectories));
        }

        return dirs.toArray(new File[]{});

    }

    protected ClassLoader getClassLoader(List<String> paths)
        throws MalformedURLException {

        URL[] urls = new URL[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            File file = new File(paths.get(i));
            urls[i] = file.toURL();
        }
        return new URLClassLoader(urls);
    }

    private Collection<Thread> getActiveThreads(ThreadGroup threadGroup)
    {
        Thread[] threads = new Thread[threadGroup.activeCount()];
        int numThreads = threadGroup.enumerate(threads);
        Collection<Thread> result = new ArrayList<Thread>(numThreads);
        for (int i = 0; i < threads.length && threads[i] != null; i++)
        {
            result.add(threads[i]);
        }
        return result;
    }

    private void joinThreads(ThreadGroup threadGroup) {
        boolean found;
        do {
            found = false;
            Collection<Thread> threads = getActiveThreads(threadGroup);
            for (Iterator<Thread> iter = threads.iterator(); iter.hasNext(); ) {
                Thread thread = iter.next();
                try {
                    getLog().debug("Joining thread " + thread.toString());
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    getLog().warn("Interrupted while waiting for " + thread.toString(), e);
                }

                found = true; // new threads may have been created
            }
        } while (found);
    }

    protected void callClojureWith(
            File[] sourceDirectory,
            File outputDirectory,
            List<String> compileClasspathElements,
            final String mainClassName,
            final String[] clojureArgs) throws MojoExecutionException {
        try {
            outputDirectory.mkdirs();

            List<String> classpath = new ArrayList<String>();
            classpath.addAll(compileClasspathElements);

            for (File directory : sourceDirectory) {
                classpath.add(directory.getPath());
            }

            classpath.add(outputDirectory.getPath());

            getLog().debug("Clojure classpath: " + classpath.toString());

            System.setProperty("clojure.compile.path", outputDirectory.getPath());

            ClassLoader classloader = getClassLoader(classpath);
            /*
              if (prependClasses != null) {
              cl.addArguments(prependClasses.toArray(new String[prependClasses.size()]));
              }
            */
            ThreadGroup threadGroup = new ThreadGroup("clojure");
            Thread thread = new Thread(threadGroup, new Runnable() {
                    public void run() {
                        try {
                            Class mainClass = Thread.currentThread().getContextClassLoader().loadClass(mainClassName);
                            Method mainMethod = mainClass.getMethod("main", new Class[]{String[].class});
                            if (!mainMethod.isAccessible()) {
                                getLog().debug( "Setting accessibility true to invoke main()." );
                                mainMethod.setAccessible( true );
                            }
                            mainMethod.invoke(null, new Object[]{clojureArgs});
                        } catch (NoSuchMethodException e) {
                            Thread.currentThread().getThreadGroup().uncaughtException( 
                                Thread.currentThread(),
                                new Exception("Missing main method with appropriate signature.", e));

                        } catch (Exception e) {
                            Thread.currentThread().getThreadGroup().uncaughtException(Thread.currentThread(), e);
                        }
                    }
                });
            thread.setContextClassLoader(classloader);
            thread.start();
            joinThreads(threadGroup);
        } catch (Exception e) {
            throw new MojoExecutionException("Calling Clojure failed", e);
        }
    }
}

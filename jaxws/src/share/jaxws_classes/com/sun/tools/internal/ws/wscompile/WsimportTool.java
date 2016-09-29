/*
 * Copyright (c) 1997, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.internal.ws.wscompile;

import com.sun.codemodel.internal.CodeWriter;
import com.sun.codemodel.internal.writer.ProgressCodeWriter;
import com.sun.tools.internal.ws.ToolVersion;
import com.sun.tools.internal.ws.api.TJavaGeneratorExtension;
import com.sun.tools.internal.ws.processor.generator.CustomExceptionGenerator;
import com.sun.tools.internal.ws.processor.generator.GeneratorBase;
import com.sun.tools.internal.ws.processor.generator.SeiGenerator;
import com.sun.tools.internal.ws.processor.generator.ServiceGenerator;
import com.sun.tools.internal.ws.processor.generator.JwsImplGenerator;
import com.sun.tools.internal.ws.processor.model.Model;
import com.sun.tools.internal.ws.processor.modeler.wsdl.ConsoleErrorReporter;
import com.sun.tools.internal.ws.processor.modeler.wsdl.WSDLModeler;
import com.sun.tools.internal.ws.processor.util.DirectoryUtil;
import com.sun.tools.internal.ws.resources.WscompileMessages;
import com.sun.tools.internal.ws.resources.WsdlMessages;
import com.sun.tools.internal.ws.util.WSDLFetcher;
import com.sun.tools.internal.ws.wsdl.parser.MetadataFinder;
import com.sun.tools.internal.ws.wsdl.parser.WSDLInternalizationLogic;
import com.sun.tools.internal.xjc.util.NullStream;
import com.sun.xml.internal.ws.api.server.Container;
import com.sun.xml.internal.ws.util.ServiceFinder;
import com.sun.istack.internal.tools.ParallelWorldClassLoader;
import org.xml.sax.EntityResolver;
import org.xml.sax.SAXParseException;

import javax.xml.bind.JAXBPermission;
import javax.xml.stream.*;
import javax.xml.ws.EndpointContext;
import java.io.*;
import java.util.*;
import java.net.Authenticator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.xml.sax.SAXException;

/**
 * @author Vivek Pandey
 */
public class WsimportTool {
    private static final String WSIMPORT = "wsimport";
    private final PrintStream out;
    private final Container container;

    /**
     * Wsimport specific options
     */
    protected WsimportOptions options = new WsimportOptions();

    public WsimportTool(OutputStream out) {
        this(out, null);
    }

    public WsimportTool(OutputStream logStream, Container container) {
        this.out = (logStream instanceof PrintStream)?(PrintStream)logStream:new PrintStream(logStream);
        this.container = container;
    }

    protected class Listener extends WsimportListener {
        ConsoleErrorReporter cer = new ConsoleErrorReporter(out == null ? new PrintStream(new NullStream()) : out);

        @Override
        public void generatedFile(String fileName) {
            message(fileName);
        }

        @Override
        public void message(String msg) {
            out.println(msg);
        }

        @Override
        public void error(SAXParseException exception) {
            cer.error(exception);
        }

        @Override
        public void fatalError(SAXParseException exception) {
            cer.fatalError(exception);
        }

        @Override
        public void warning(SAXParseException exception) {
            cer.warning(exception);
        }

        @Override
        public void debug(SAXParseException exception) {
            cer.debug(exception);
        }

        @Override
        public void info(SAXParseException exception) {
            cer.info(exception);
        }

        public void enableDebugging(){
            cer.enableDebugging();
        }
    }

    protected class Receiver extends ErrorReceiverFilter {

        private Listener listener;

        public Receiver(Listener listener) {
            super(listener);
            this.listener = listener;
        }

        public void info(SAXParseException exception) {
            if (options.verbose)
                super.info(exception);
        }

        public void warning(SAXParseException exception) {
            if (!options.quiet)
                super.warning(exception);
        }

        @Override
        public void pollAbort() throws AbortException {
            if (listener.isCanceled())
                throw new AbortException();
        }

        @Override
        public void debug(SAXParseException exception){
            if(options.debugMode){
                listener.debug(exception);
            }
        }
    }

    public boolean run(String[] args) {
        Listener listener = new Listener();
        Receiver receiver = new Receiver(listener);
        return run(args, listener, receiver);
    }

    protected boolean run(String[] args, Listener listener,
                       Receiver receiver) {
        for (String arg : args) {
            if (arg.equals("-version")) {
                listener.message(
                        WscompileMessages.WSIMPORT_VERSION(ToolVersion.VERSION.MAJOR_VERSION));
                return true;
            }
            if (arg.equals("-fullversion")) {
                listener.message(
                        WscompileMessages.WSIMPORT_FULLVERSION(ToolVersion.VERSION.toString()));
                return true;
            }
        }

        Authenticator orig = null;
        try {
            parseArguments(args, listener, receiver);

            try {
                orig = DefaultAuthenticator.getCurrentAuthenticator();

                Model wsdlModel = buildWsdlModel(listener, receiver);
                if (wsdlModel == null)
                   return false;

                if (!generateCode(listener, receiver, wsdlModel, true))
                   return false;

                /* Not so fast!
            } catch(AbortException e){
                //error might have been reported
                 *
                 */
            }catch (IOException e) {
                receiver.error(e);
                return false;
            }catch (XMLStreamException e) {
                receiver.error(e);
                return false;
            }
            if (!options.nocompile){
                if(!compileGeneratedClasses(receiver, listener)){
                    listener.message(WscompileMessages.WSCOMPILE_COMPILATION_FAILED());
                    return false;
                }
            }
            try {
                if (options.clientjar != null) {
                    //add all the generated class files to the list of generated files
                    addClassesToGeneratedFiles();
                    jarArtifacts(listener);

                }
            } catch (IOException e) {
                receiver.error(e);
                return false;
            }

        } catch (Options.WeAreDone done) {
            usage(done.getOptions());
        } catch (BadCommandLineException e) {
            if (e.getMessage() != null) {
                System.out.println(e.getMessage());
                System.out.println();
            }
            usage(e.getOptions());
            return false;
        } finally{
            deleteGeneratedFiles();
            if (!options.disableAuthenticator) {
                Authenticator.setDefault(orig);
            }
        }
        if(receiver.hadError()) {
            return false;
        }
        return true;
    }

    private void deleteGeneratedFiles() {
        Set<File> trackedRootPackages = new HashSet<File>();

        if (options.clientjar != null) {
            //remove all non-java artifacts as they will packaged in jar.
            Iterable<File> generatedFiles = options.getGeneratedFiles();
            synchronized (generatedFiles) {
                for (File file : generatedFiles) {
                    if (!file.getName().endsWith(".java")) {
                        file.delete();
                        trackedRootPackages.add(file.getParentFile());

                    }

                }
            }
            //remove empty package dirs
            for(File pkg:trackedRootPackages) {

                while(pkg.list() != null && pkg.list().length ==0 && !pkg.equals(options.destDir)) {
                    File parentPkg = pkg.getParentFile();
                    pkg.delete();
                    pkg = parentPkg;
                }
            }
        }
        if(!options.keep) {
            options.removeGeneratedFiles();
        }

    }

    private void addClassesToGeneratedFiles() throws IOException {
        Iterable<File> generatedFiles = options.getGeneratedFiles();
        final List<File> trackedClassFiles = new ArrayList<File>();
        for(File f: generatedFiles) {
            if(f.getName().endsWith(".java")) {
                String relativeDir = DirectoryUtil.getRelativePathfromCommonBase(f.getParentFile(),options.sourceDir);
                final String className = f.getName().substring(0,f.getName().indexOf(".java"));
                File classDir = new File(options.destDir,relativeDir);
                if(classDir.exists()) {
                    classDir.listFiles(new FilenameFilter() {

                        public boolean accept(File dir, String name) {
                            if(name.equals(className+".class") || (name.startsWith(className+"$") && name.endsWith(".class"))) {
                                trackedClassFiles.add(new File(dir,name));
                                return true;
                            }
                            return false;
                        }
                    });
                }
            }
        }
        for(File f: trackedClassFiles) {
            options.addGeneratedFile(f);
        }
    }

    private void jarArtifacts(WsimportListener listener) throws IOException {
        File zipFile = new File(options.clientjar);
        if(!zipFile.isAbsolute()) {
            zipFile = new File(options.destDir, options.clientjar);
        }

        if (zipFile.exists()) {
            //TODO
        }
        FileOutputStream fos = null;
        if( !options.quiet )
            listener.message(WscompileMessages.WSIMPORT_ARCHIVING_ARTIFACTS(zipFile));


        fos = new FileOutputStream(zipFile);
        JarOutputStream jos = new JarOutputStream(fos);

        String base = options.destDir.getCanonicalPath();
        for(File f: options.getGeneratedFiles()) {
            //exclude packaging the java files in the jar
            if(f.getName().endsWith(".java")) {
                continue;
            }
            if(options.verbose) {
                listener.message(WscompileMessages.WSIMPORT_ARCHIVE_ARTIFACT(f, options.clientjar));
            }
            String entry = f.getCanonicalPath().substring(base.length()+1);
            BufferedInputStream bis = new BufferedInputStream(
                            new FileInputStream(f));
            JarEntry jarEntry = new JarEntry(entry);
            jos.putNextEntry(jarEntry);
            int bytesRead;
            byte[] buffer = new byte[1024];
            while ((bytesRead = bis.read(buffer)) != -1) {
                jos.write(buffer, 0, bytesRead);
            }
            bis.close();

        }

        jos.close();

    }

    protected void parseArguments(String[] args, Listener listener,
                                  Receiver receiver) throws BadCommandLineException {
        options.parseArguments(args);
        options.validate();
        if (options.debugMode)
            listener.enableDebugging();
        options.parseBindings(receiver);
    }

    protected Model buildWsdlModel(Listener listener,
                                   Receiver receiver) throws BadCommandLineException, XMLStreamException, IOException {
        if( !options.quiet )
            listener.message(WscompileMessages.WSIMPORT_PARSING_WSDL());

        //set auth info
        //if(options.authFile != null)
        if (!options.disableAuthenticator) {
            Authenticator.setDefault(new DefaultAuthenticator(receiver, options.authFile));
        }

        MetadataFinder forest = new MetadataFinder(new WSDLInternalizationLogic(), options, receiver);
        forest.parseWSDL();
        if (forest.isMexMetadata)
            receiver.reset();

        WSDLModeler wsdlModeler = new WSDLModeler(options, receiver,forest);
        Model wsdlModel = wsdlModeler.buildModel();
        if (wsdlModel == null) {
            listener.message(WsdlMessages.PARSING_PARSE_FAILED());
        }

        if(options.clientjar != null) {
            if( !options.quiet )
                listener.message(WscompileMessages.WSIMPORT_FETCHING_METADATA());
            options.wsdlLocation = new WSDLFetcher(options,listener).fetchWsdls(forest);
        }

        return wsdlModel;
    }

    protected boolean generateCode(Listener listener, Receiver receiver,
                                   Model wsdlModel, boolean generateService)
                                   throws IOException {
        //generated code
        if( !options.quiet )
            listener.message(WscompileMessages.WSIMPORT_GENERATING_CODE());

        TJavaGeneratorExtension[] genExtn = ServiceFinder.find(TJavaGeneratorExtension.class).toArray();
        CustomExceptionGenerator.generate(wsdlModel,  options, receiver);
            SeiGenerator.generate(wsdlModel, options, receiver, genExtn);
        if(receiver.hadError()){
            throw new AbortException();
        }
        if (generateService)
        {
            ServiceGenerator.generate(wsdlModel, options, receiver);
        }
        for (GeneratorBase g : ServiceFinder.find(GeneratorBase.class)) {
            g.init(wsdlModel, options, receiver);
            g.doGeneration();
        }

        List<String> implFiles = null;
       if (options.isGenerateJWS) {
                implFiles = JwsImplGenerator.generate(wsdlModel, options, receiver);
       }

        for (Plugin plugin: options.activePlugins) {
            try {
                plugin.run(wsdlModel, options, receiver);
            } catch (SAXException sex) {
                // fatal error. error should have been reported
                return false;
            }
        }

        CodeWriter cw;
        if (options.filer != null) {
            cw = new FilerCodeWriter(options.sourceDir, options);
        } else {
            cw = new WSCodeWriter(options.sourceDir, options);
        }

        if (options.verbose)
            cw = new ProgressCodeWriter(cw, out);
        options.getCodeModel().build(cw);

        if (options.isGenerateJWS) {
                //move Impl files to implDestDir
                return JwsImplGenerator.moveToImplDestDir(implFiles, options, receiver);
       }

       return true;
    }

    public void setEntityResolver(EntityResolver resolver){
        this.options.entityResolver = resolver;
    }

    /*
     * To take care of JDK6-JDK6u3, where 2.1 API classes are not there
     */
    private static boolean useBootClasspath(Class clazz) {
        try {
            ParallelWorldClassLoader.toJarUrl(clazz.getResource('/'+clazz.getName().replace('.','/')+".class"));
            return true;
        } catch(Exception e) {
            return false;
        }
    }

    protected boolean compileGeneratedClasses(ErrorReceiver receiver, WsimportListener listener){
        List<String> sourceFiles = new ArrayList<String>();

        for (File f : options.getGeneratedFiles()) {
            if (f.exists() && f.getName().endsWith(".java")) {
                sourceFiles.add(f.getAbsolutePath());
            }
        }

        if (sourceFiles.size() > 0) {
            String classDir = options.destDir.getAbsolutePath();
            String classpathString = createClasspathString();
            boolean bootCP = useBootClasspath(EndpointContext.class) || useBootClasspath(JAXBPermission.class);
            String[] args = new String[4 + (bootCP ? 1 : 0) + (options.debug ? 1 : 0)
                    + (options.encoding != null ? 2 : 0) + sourceFiles.size()];
            args[0] = "-d";
            args[1] = classDir;
            args[2] = "-classpath";
            args[3] = classpathString;
            int baseIndex = 4;
            //javac is not working in osgi as the url starts with a bundle
            if (bootCP) {
                args[baseIndex++] = "-Xbootclasspath/p:"+JavaCompilerHelper.getJarFile(EndpointContext.class)+File.pathSeparator+JavaCompilerHelper.getJarFile(JAXBPermission.class);
            }

            if (options.debug) {
                args[baseIndex++] = "-g";
            }

            if (options.encoding != null) {
                args[baseIndex++] = "-encoding";
                args[baseIndex++] = options.encoding;
            }

            for (int i = 0; i < sourceFiles.size(); ++i) {
                args[baseIndex + i] = sourceFiles.get(i);
            }

            listener.message(WscompileMessages.WSIMPORT_COMPILING_CODE());
            if(options.verbose){
                StringBuffer argstr = new StringBuffer();
                for(String arg:args){
                    argstr.append(arg).append(" ");
                }
                listener.message("javac "+ argstr.toString());
            }

            return JavaCompilerHelper.compile(args, out, receiver);
        }
        //there are no files to compile, so return true?
        return true;
    }

    private String createClasspathString() {
        StringBuilder classpathStr = new StringBuilder(System.getProperty("java.class.path"));
        for(String s: options.cmdlineJars) {
            classpathStr.append(File.pathSeparator);
            classpathStr.append(new File(s).toString());
        }
        return classpathStr.toString();
    }

    protected void usage(Options options) {
        System.out.println(WscompileMessages.WSIMPORT_HELP(WSIMPORT));
        System.out.println(WscompileMessages.WSIMPORT_USAGE_EXTENSIONS());
        System.out.println(WscompileMessages.WSIMPORT_USAGE_EXAMPLES());
    }
}

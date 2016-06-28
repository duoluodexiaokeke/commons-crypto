/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.crypto;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.crypto.conf.ConfigurationKeys;

/**
 * A helper to load the native code i.e. libcommons-crypto.so. This handles the
 * fallback to either the bundled libcommons-crypto-Linux-i386-32.so or the
 * default java implementations where appropriate.
 */
final class NativeCodeLoader {

    private final static boolean nativeCodeLoaded;
    /**
     * The private constructor of {@link NativeCodeLoader}.
     */
    private NativeCodeLoader() {
    }

    static {
        // Try to load native library and set fallback flag appropriately
        boolean nativeLoaded = false;

        //Trying to load the custom-built native-commons-crypto library...");
        try {
            File nativeLibFile = findNativeLibrary();
            if (nativeLibFile != null) {
                // Load extracted or specified native library.
                System.load(nativeLibFile.getAbsolutePath());
            } else {
                // Load preinstalled library (in the path -Djava.library.path)
                System.loadLibrary("commons-crypto");
            }
            // Loaded the native library
            nativeLoaded = true;
        } catch (Throwable t) {
            ;// NOPMD: Ignore failure to load
        }

        nativeCodeLoaded = nativeLoaded;
    }

    /**
     * Finds the native library.
     *
     * @return the jar file.
     */
    private static File findNativeLibrary() {
        // Try to load the library in commons-crypto.lib.path */
        String nativeLibraryPath = NativeCodeLoader.getLibPath();
        String nativeLibraryName = NativeCodeLoader.getLibName();

        // Resolve the library file name with a suffix (e.g., dll, .so, etc.)
        if (nativeLibraryName == null) {
            nativeLibraryName = System.mapLibraryName("commons-crypto");
        }
        if (nativeLibraryPath != null) {
            File nativeLib = new File(nativeLibraryPath, nativeLibraryName);
            if (nativeLib.exists()) {
                return nativeLib;
            }
        }

        // Load an OS-dependent native library inside a jar file
        nativeLibraryPath = "/org/apache/commons/crypto/native/"
                + OSInfo.getNativeLibFolderPathForCurrentOS();
        boolean hasNativeLib = hasResource(nativeLibraryPath + "/"
                + nativeLibraryName);
        if (!hasNativeLib) {
            String altName = "libcommons-crypto.jnilib";
            if (OSInfo.getOSName().equals("Mac") && hasResource(nativeLibraryPath + "/" + altName)) {
                // Fix for openjdk7 for Mac
                nativeLibraryName = altName;
                hasNativeLib = true;
            }
        }

        if (!hasNativeLib) {
            String errorMessage = String.format(
                    "no native library is found for os.name=%s and os.arch=%s",
                    OSInfo.getOSName(), OSInfo.getArchName());
            throw new RuntimeException(errorMessage);
        }

        // Temporary folder for the native lib. Use the value of
        // commons-crypto.tempdir or java.io.tmpdir
        String tempFolder = new File(NativeCodeLoader.getTmpDir()).getAbsolutePath();

        // Extract and load a native library inside the jar file
        return extractLibraryFile(nativeLibraryPath, nativeLibraryName,
                tempFolder);
    }

    /**
     * Extracts the specified library file to the target folder.
     *
     * @param libFolderForCurrentOS the library in commons-crypto.lib.path.
     * @param libraryFileName the library name.
     * @param targetFolder Target folder for the native lib. Use the value of
     *        commons-crypto.tempdir or java.io.tmpdir.
     * @return the library file.
     */
    private static File extractLibraryFile(String libFolderForCurrentOS,
            String libraryFileName, String targetFolder) {
        String nativeLibraryFilePath = libFolderForCurrentOS + "/"
                + libraryFileName;

        // Attach UUID to the native library file to ensure multiple class
        // loaders
        // can read the libcommons-crypto multiple times.
        String uuid = UUID.randomUUID().toString();
        String extractedLibFileName = String.format("commons-crypto-%s-%s-%s",
                getVersion(), uuid, libraryFileName);
        File extractedLibFile = new File(targetFolder, extractedLibFileName);

        InputStream reader = null;
        try {
            // Extract a native library file into the target directory
            reader = NativeCodeLoader.class
                    .getResourceAsStream(nativeLibraryFilePath);
            FileOutputStream writer = new FileOutputStream(extractedLibFile);
            try {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, bytesRead);
                }
            } finally {
                // Delete the extracted lib file on JVM exit.
                extractedLibFile.deleteOnExit();

                writer.close();

                if (reader != null) {
                    reader.close();
                    reader = null;
                }
            }

            // Set executable (x) flag to enable Java to load the native library
            if (!extractedLibFile.setReadable(true)
                    || !extractedLibFile.setExecutable(true)
                    || !extractedLibFile.setWritable(true, true)) {
                throw new RuntimeException("Invalid path for library path");
            }

            // Check whether the contents are properly copied from the resource
            // folder
            {
                InputStream nativeIn = null;
                InputStream extractedLibIn = null;
                try {
                    nativeIn = NativeCodeLoader.class
                            .getResourceAsStream(nativeLibraryFilePath);
                    extractedLibIn = new FileInputStream(extractedLibFile);
                    if (!contentsEquals(nativeIn, extractedLibIn)) {
                        throw new RuntimeException(String.format(
                                "Failed to write a native library file at %s",
                                extractedLibFile));
                    }
                } finally {
                    if (nativeIn != null) {
                        nativeIn.close();
                    }
                    if (extractedLibIn != null) {
                        extractedLibIn.close();
                    }
                }
            }

            return new File(targetFolder, extractedLibFileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Gets the version by reading pom.properties embedded in jar. This version
     * data is used as a suffix of a dll file extracted from the jar.
     *
     * @return the version string
     */
    static String getVersion() {
        URL versionFile = NativeCodeLoader.class
                .getResource("/META-INF/maven/org.apache.commons.crypto/commons-crypto/pom.properties");
        if (versionFile == null) {
            versionFile = NativeCodeLoader.class
                    .getResource("/org/apache/commons/crypto/VERSION");
        }
        String version = "unknown";
        try {
            if (versionFile != null) {
                Properties versionData = new Properties();
                versionData.load(versionFile.openStream());
                version = versionData.getProperty("version", version);
                if (version.equals("unknown")) {
                    version = versionData.getProperty("VERSION", version);
                }
                version = version.trim().replaceAll("[^0-9M\\.]", "");
            }
        } catch (IOException e) {
            System.err.println(e);
        }
        return version;
    }

    /**
     * Checks whether in1 and in2 is equal.
     *
     * @param in1 the input1.
     * @param in2 the input2.
     * @return true if in1 and in2 is equal, else false.
     * @throws IOException if an I/O error occurs.
     */
    private static boolean contentsEquals(InputStream in1, InputStream in2)
            throws IOException {
        if (!(in1 instanceof BufferedInputStream)) {
            in1 = new BufferedInputStream(in1);
        }
        if (!(in2 instanceof BufferedInputStream)) {
            in2 = new BufferedInputStream(in2);
        }

        int ch = in1.read();
        while (ch != -1) {
            int ch2 = in2.read();
            if (ch != ch2) {
                return false;
            }
            ch = in1.read();
        }
        int ch2 = in2.read();
        return ch2 == -1;
    }

    /**
     * Checks whether the given path has resource.
     *
     * @param path the path.
     * @return the boolean.
     */
    private static boolean hasResource(String path) {
        return NativeCodeLoader.class.getResource(path) != null;
    }

    /**
     * Checks whether native code is loaded for this platform.
     *
     * @return <code>true</code> if native is loaded, else <code>false</code>.
     */
    static boolean isNativeCodeLoaded() {
        return nativeCodeLoaded;
    }

    /**
     * Gets the temp directory for extracting crypto library.
     *
     * @return the temp directory.
     */
    private static String getTmpDir() {
        return System.getProperty(ConfigurationKeys.LIB_TEMPDIR_KEY,
                System.getProperty("java.io.tmpdir"));
    }

    /**
     * Gets the file name of native library.
     *
     * @return the file name of native library.
     */
    private static String getLibName() {
        return System.getProperty(ConfigurationKeys.LIB_NAME_KEY);
    }

    /**
     * Gets path of native library.
     *
     * @return the path of native library.
     */
    private static String getLibPath() {
        return System.getProperty(ConfigurationKeys.LIB_PATH_KEY);
    }
}
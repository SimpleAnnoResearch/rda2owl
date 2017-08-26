package de.ontomed.simpleanno;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads the latest release of the RDA vocabulary and performs a syntactic transformation to OWL ontology files.
 */
public class RDA2OWL {

    private static final Pattern entityDefinitionStartPattern = Pattern.compile("\\<rdf:Description rdf:about=\"(.+?)\"\\>");
    private static final Pattern entityDefinitionEndPattern = Pattern.compile("\\</rdf:Description\\>");

    public static void main(String[] args) {


        System.out.println("Checking latest RDA release version...");

        // Determine latest RDA version number (request HTML page from
        // https://github.com/RDARegistry/RDA-Vocabularies/releases/latest, this will redirect to the actual latest
        // release page. The release number and a link to the file can then be found in the html source).

        String latestRDAVersion = null;

        HttpClient httpclient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setCookieSpec(CookieSpecs.STANDARD).build())
                .build();

        try {
            HttpGet httpget = new HttpGet("http://github.com/RDARegistry/RDA-Vocabularies/releases/latest");

            ResponseHandler<String> responseHandler = new ResponseHandler<String>() {

                @Override
                public String handleResponse(
                        final HttpResponse response) throws ClientProtocolException, IOException {
                    int status = response.getStatusLine().getStatusCode();
                    if (status >= 200 && status < 300) {
                        HttpEntity entity = response.getEntity();
                        return entity != null ? EntityUtils.toString(entity) : null;
                    } else {
                        throw new ClientProtocolException("Unexpected response status: " + status);
                    }
                }

            };
            String responseBody = httpclient.execute(httpget, responseHandler);

            Pattern versionPattern = Pattern.compile("\\<a href=\"/RDARegistry/RDA-Vocabularies/archive/v(.*?)\\.zip\"");
            Matcher versionMatcher = versionPattern.matcher(responseBody);
            if (versionMatcher.find()) {
                latestRDAVersion = versionMatcher.group(1);
                System.out.println("Latest RDA release version: " + latestRDAVersion);
            } else {
                System.out.println("Could not detect latest RDA release version. Maybe the github page layout has changed. Please contact the developer of this tool.");
                System.exit(1);
            }

        } catch (IOException ex) {
            System.out.println("Error retrieving latest RDA release version from github.");
            System.out.println(ex.getMessage());
            System.exit(1);
        }


        // download latest release to tmp folder

        // TODO check for previously downloaded file (maybe do an MD5 checksum comparison in order to check it's really the file)

        System.out.println("Downloading latest RDA release to tmp folder...");

        File rootFolder = new File(System.getProperty("java.io.tmpdir"), "rda2owl");

        File zipFile = new File(rootFolder, "RDA-" + latestRDAVersion + ".zip");

        if (!zipFile.exists()) {

            rootFolder.delete();
            rootFolder.mkdirs();

            try {
                HttpGet httpget = new HttpGet("http://github.com/RDARegistry/RDA-Vocabularies/archive/v" + latestRDAVersion + ".zip");
                HttpResponse response = httpclient.execute(httpget);
                HttpEntity entity = response.getEntity();

                InputStream in = entity.getContent();
                FileOutputStream out = new FileOutputStream(zipFile);
                IOUtils.copy(in, out);
                IOUtils.closeQuietly(out);

                System.out.println("File downloaded to " + zipFile.getAbsolutePath());
            } catch (IOException ex) {
                System.out.println("Error downloading file.");
                System.exit(1);
            }

            System.out.println("Extracting zip file...");

            try {
                ZipFile zip = new ZipFile(zipFile);

                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File entryDestination = new File(rootFolder, entry.getName());
                    if (entry.isDirectory()) {
                        entryDestination.mkdirs();
                    } else {
                        entryDestination.getParentFile().mkdirs();
                        InputStream in = zip.getInputStream(entry);
                        OutputStream out = new FileOutputStream(entryDestination);
                        IOUtils.copy(in, out);
                        IOUtils.closeQuietly(in);
                        IOUtils.closeQuietly(out);
                    }
                }
            } catch (IOException ex) {
                System.out.println("Error extracting zip file.");
                System.out.println(ex.getMessage());
                System.exit(1);
            }
        } else {
            System.out.println("Found file in local file system.");
        }

        System.out.println("\nStarting conversion...");

        File rdaBaseFolder = new File(rootFolder, "RDA-Vocabularies-" + latestRDAVersion);
        File rdfxmlBaseFolder = new File(rdaBaseFolder, "xml");

        File elementsFolder = new File(rdfxmlBaseFolder, "Elements");

        File owlDestFolder = new File(rdaBaseFolder, "owl");
        owlDestFolder.mkdirs();

        Arrays.stream(elementsFolder.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().endsWith(".xml");
            }
        })).forEach(file -> {
            // the RDA RDF files obey a certain structure, on which we rely when processing the files:
            // - If a file named <name>.xml is accompanied by a folder named <name>, then the file contains only property definitions.
            //   The folder then contains two additional files, named datatype.xml and object.xml. All three files define almost the same properties redunandtly (but in different namespaces).
            //   All properties have the type rdf:property. The properties defined in object.xml and datatype.xml are subproperties of the corresponding properties defined in <name>.xml.
            //   Only "real" datatype properties (whose range is not a class) do not have a corresponding property in object.xml.
            //   It is not possible to maintain this structure in OWL obviously, because datatype properties cannot be subproperties of object properties and vice versa.
            //   What we do instead is go through all properties in datatype.xml and, for each property, check for the definition of  an equally named property in object.xml.
            //   If such property exists, then we define an object property in our OWL ontology. Otherwise, we add it as a datatype property.
            //   <name>.xml contains additional annotations (labels, definitions, etc.). We add all these annotations as well.
            // - If no accompanying folder is present, then the file only contains class or property (rdf:property) definitions. Here we have no clue about whether a property is an
            //   object or a datatype property. Therefore, we just add them as annotations properties (which in most cases seems to be the intention anyway).

            String fileName = file.getName();
            String folderName = file.getName().substring(0, fileName.length() - 4);
            File folder = new File(elementsFolder, folderName);

            try {
                if (folder.exists() && folder.isDirectory()) {
                    handlePropertyFiles(file, folder);
                } else {
                    handleSingleFile(file);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        });
    }

    private static void handlePropertyFiles(File mainFile, File folder) throws IOException {
        HashMap<String, HashSet<String>> annotationProperties = new HashMap<>();
        HashMap<String, HashSet<String>> objectProperties = new HashMap<>();
        HashMap<String, HashSet<String>> datatypeProperties = new HashMap<>();


        handleSingleFile(mainFile);
        handleSingleFile(new File(folder, "datatype.xml"));
        handleSingleFile(new File(folder, "object.xml"));
    }

    private static void handleSingleFile(File file) throws IOException {
        System.out.println("\nFile: " + file.getAbsolutePath());
        BufferedReader in = new BufferedReader(new FileReader(file));

        HashMap<String, HashSet<String>> entities = new HashMap<>();

        collectEntities(in, entities);
    }

    private static <M extends Map<String, S>, S extends Set<String>> void collectEntities(BufferedReader in, M map) throws IOException {
        String line = null;
        boolean insideEntity = false;
        HashSet<String> currentEntity = null;

        while ((line = in.readLine()) != null) {

            String newEntityURI = startOfEntity(line);
            if (newEntityURI != null) {
                currentEntity = new HashSet<>();
                insideEntity = true;
                System.out.println(normalizeNameSpace(newEntityURI));
            } else if (isEndOfEntity(line)) {
                insideEntity = false;
                System.out.println(line);
            } else if (insideEntity) {
                currentEntity.add(line.trim());
                System.out.println("    " + line);
            }
        }

    }

    private static String startOfEntity(String line) {
        Matcher matcher = entityDefinitionStartPattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isEndOfEntity(String line) {
        return entityDefinitionEndPattern.matcher(line).find();
    }


    /**
     * Strips the "datatype" or "object" path element from the given namespace URI
     * @param orig a namespace URI
     * @return The same namespace URI without "datatype" or "object" path element
     */
    private static String normalizeNameSpace(String orig) {
        return orig.replaceAll("/datatype/", "/").replaceAll("/object/", "/");
    }
}

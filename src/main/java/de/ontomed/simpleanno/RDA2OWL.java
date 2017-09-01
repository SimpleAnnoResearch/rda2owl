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

    private static class EntityDescription {
        private TYPE type;
        HashSet<String> lines;
    }

    private static final String XMLNS_PREFIX = "xmlns:";

    private static final String NS_OWL = "http://www.w3.org/2002/07/owl#";

    private enum TYPE {
        CLASS("Class"),
        INDIVIDUAL("Individual"),
        OBJECT_PROPERTY("ObjectProperty"),
        DATATYPE_PROPERTY("DatatypeProperty"),
        ANNOTATION_PROPERTY("AnnotationProperty");

        private String element;
        TYPE(String element) {this.element = element;}
        public String element() {return element;}
    }

    private static final Pattern entityDefinitionStartPattern = Pattern.compile("\\<rdf:Description rdf:about=\"(.+?)\"\\>");
    private static final Pattern entityDefinitionEndPattern = Pattern.compile("\\</rdf:Description\\>");

    private static final Pattern entityTypePattern = Pattern.compile("\\<rdf:type rdf:resource=\"(.+?)\"\\s*\\/\\>");

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
                System.out.format("Latest RDA release version: %s\n", latestRDAVersion);
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

        // check for previously downloaded file
        // TODO maybe do an MD5 checksum comparison in order to check it's really the file

        File rootFolder = new File(System.getProperty("java.io.tmpdir"), "rda2owl");

        System.out.format("Downloading latest RDA release to %s ...\n", rootFolder.getAbsolutePath());

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

                System.out.format("File downloaded to %s\n", zipFile.getAbsolutePath());
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
            System.out.println("Found already existing file in local file system.");
        }

        System.out.println("\nStarting conversion...");

        File rdaBaseFolder = new File(rootFolder, "RDA-Vocabularies-" + latestRDAVersion);
        File rdfxmlBaseFolder = new File(rdaBaseFolder, "xml");

        File elementsFolder = new File(rdfxmlBaseFolder, "Elements");
        File termListFolder = new File(rdfxmlBaseFolder, "termList");

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
                    handlePropertyFiles(file, folder, owlDestFolder);
                } else {
                    handleSingleFile(file, owlDestFolder);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        });

        Arrays.stream(termListFolder.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().endsWith(".xml");
            }
        })).forEach(file -> {
            try {
                handleSingleFile(file, owlDestFolder);
            } catch (IOException e) {
                e.printStackTrace();
            }

        });

    }

    private static void handlePropertyFiles(File mainFile, File folder, File outputRootFolder) throws IOException {
//        HashMap<String, HashSet<String>> annotationProperties = new HashMap<>();
//        HashMap<String, HashSet<String>> objectProperties = new HashMap<>();
//        HashMap<String, HashSet<String>> datatypeProperties = new HashMap<>();

        handleSingleFile(mainFile, outputRootFolder);
        handleSingleFile(new File(folder, "datatype.xml"), outputRootFolder);
        handleSingleFile(new File(folder, "object.xml"), outputRootFolder);
    }

    private static void handleSingleFile(File inputFile, File outputRootFolder) throws IOException {

        System.out.format("\nInput file: %s\n", inputFile.getAbsolutePath());
        File outputFile = getOutputFile(inputFile, outputRootFolder);

        BufferedReader in = new BufferedReader(new FileReader(inputFile));

        FileWriter out = new FileWriter(outputFile);

//        HashMap<String, HashSet<String>> entities = new HashMap<>();
//        collectEntities(in, entities);

        String fileName = inputFile.getName();
        TYPE propertyTypeHint;
        switch (fileName) {
            case "datatype.xml": propertyTypeHint = TYPE.DATATYPE_PROPERTY; break;
            case "object.xml": propertyTypeHint = TYPE.OBJECT_PROPERTY; break;
            default: propertyTypeHint = TYPE.ANNOTATION_PROPERTY; break;
        }


        handleNamespaces(in, out);

        if (inputFile.getParentFile().getName().equals("Elements")) {
            handleOntologyHeader(in, out);
        } else {
            handleSkosOntologyHeader(in, out);
        }


        while  (handleNextEntity(in, out, propertyTypeHint));

        in.close();
        out.close();
    }

    private static void handleNamespaces(BufferedReader in, Writer out) throws IOException {
        LinkedList<String> lines = new LinkedList<>();
        String line = null;

        // fast forward to xmlns:
        while (!(line = in.readLine()).trim().startsWith("xmlns:")) {
            out.write(line);
            out.write("\n");
        }

        boolean containsOwlNS = false;

        while (line.trim().startsWith("xmlns:")) {
            lines.add(line);
            if (line.contains(NS_OWL)) {
                containsOwlNS = true;
            }
            line = in.readLine();
        }

        if (!containsOwlNS) {
            lines.add(lines.size() - 1, "    " + XMLNS_PREFIX + "owl=\"" + NS_OWL + "\"" );
        }

        for (String nsLine : lines) {
            out.write(nsLine);
            out.write("\n");
        }

        out.write(line);
        out.write("\n");

    }

    // transforms the first rdf:Description to an owl:Ontology declaration
    private static void handleOntologyHeader(BufferedReader in, Writer out) throws IOException {
        String line = null;

        while ((line = in.readLine()) != null) {
            String ontologyURI = startOfEntity(line);
            if (ontologyURI != null) {
                line = "<owl:Ontology rdf:about=\"" + ontologyURI + "\">";
            }
            if (isEndOfEntity(line)) {
                out.write("</owl:Ontology>\n");
                break;
            }
            out.write(line + "\n");

        }
    }

    private static final Pattern SKOS_CONCEPT_SCHEME_START_PATTERN = Pattern.compile("<skos:ConceptScheme rdf:about=\"(.*?)\">");
    private static final Pattern SKOS_CONCEPT_SCHEME_END_PATTERN = Pattern.compile("</skos:ConceptScheme>");

    private static void handleSkosOntologyHeader(BufferedReader in, Writer out) throws IOException {
        String ontologyURI = null;
        LinkedList<String> lines = new LinkedList<>();
        String line = null;

        while ((line = in.readLine()) != null) {
            lines.add(line);
            Matcher matcher = SKOS_CONCEPT_SCHEME_START_PATTERN.matcher(line);
            if (matcher.find()) {
                ontologyURI = matcher.group(1);
            } else {
                matcher = SKOS_CONCEPT_SCHEME_START_PATTERN.matcher(line);
                if (matcher.find()) {
                    break;
                }
            }
        }

        lines.addFirst("<owl:Ontology rdf:about=\"" + ontologyURI + "\" />");

        for (String actualLine : lines) {
            out.write(actualLine + "\n");
        }

    }

    private static boolean handleNextEntity(BufferedReader in, Writer out, TYPE fileSpecificPropertyType) throws IOException {
        String line = null;
        LinkedList<String> lines = new LinkedList<>();

        String entityURI = null;
        TYPE entityType = null;

        while ((line = in.readLine()) != null) {

            if (entityURI == null) {
                // still fast-forwarding to the start of the next entity
                entityURI = startOfEntity(line);
                if (entityURI == null) {
                    // still no entity start
                    continue;
                }
            }


            // we are inside of an entity

            // look for type element
            Matcher entityTypeMatcher = entityTypePattern.matcher(line);
            if (entityTypeMatcher.find()) {
                // this is the line that contains the rdf:type element
                // we extract the type and create a proper element instead of the rdf:Description element
                String entityTypeURI = entityTypeMatcher.group(1);
                String entityTypeName = entityTypeURI.substring(entityTypeURI.lastIndexOf('#') + 1);
                switch (entityTypeName.toLowerCase()) {
                    case "class": entityType = TYPE.CLASS; break;
                    case "individual": entityType = TYPE.INDIVIDUAL; break;
                    case "objectproperty": entityType = TYPE.OBJECT_PROPERTY; break;
                    case "property": entityType = fileSpecificPropertyType; break;
                    default: break;
                }
            } else if (isEndOfEntity(line)) {
                // this is the end, my friend

                lines.add(line);

                if (entityType != null) {
                    // we need to replace the rdf:Description start and end tags (first and last line) with the concrete owl entity elements
                    String newStartLine = "<owl:" + entityType.element + " rdf:about=\"" + entityURI + "\">";
                    lines.removeFirst();
                    lines.addFirst(newStartLine);

                    String newEndLine = "</owl:" + entityType.element + ">";
                    lines.removeLast();
                    lines.add(newEndLine);
                }

                for (String actualLine : lines) {
                    out.write(actualLine + "\n");
                }

                return true;

            } else {
                // regular line, copy it
                lines.add(line);
            }

        }
        // no more entities, bye bye
        return false;

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

    private final static Pattern xmlFileNameBasePattern = Pattern.compile("(\\w*)\\.xml");

    private static File getOutputFile(File inputFile, File outputRootFolder) {
        String inputFileName = inputFile.getName();

        Matcher matcher = xmlFileNameBasePattern.matcher(inputFileName);
        if (!matcher.find()) {
            throw  new IllegalArgumentException("Unexpected file name: " + inputFile.getAbsolutePath());
        }

        String outputFileName = matcher.group(1) + ".owl";

        File outputFile;

        if (inputFileName.equals("datatype.xml") || inputFileName.equals("object.xml")) {
            String inputSubFolderName = inputFile.getParentFile().getParentFile().getName();
            String inputFolderName = inputFile.getParentFile().getName(); // should always be "Elements"
            outputFile =new File(new File(new File(outputRootFolder, inputSubFolderName), inputFolderName), outputFileName);
        } else {
            String inputFolderName = inputFile.getParentFile().getName(); // either "Elements" or "termList"
            outputFile = new File(new File(outputRootFolder, inputFolderName), outputFileName);
        }

        File outputFolder = outputFile.getParentFile();
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }

        return outputFile;

    }
}
